package polyllvm.extension;

import org.bytedeco.javacpp.PointerPointer;
import polyglot.ast.Node;
import polyglot.types.TypeSystem;
import polyglot.util.SerialVersionUID;
import polyllvm.ast.PolyLLVMExt;
import polyllvm.util.Constants;
import polyllvm.visit.LLVMTranslator;

import java.lang.Override;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.bytedeco.javacpp.LLVM.*;

public class PolyLLVMSourceFileExt extends PolyLLVMExt {
    private static final long serialVersionUID = SerialVersionUID.generate();

    @Override
    public LLVMTranslator enterTranslatePseudoLLVM(LLVMTranslator v) {
        // Add a calloc declaration to the current module (declare i8* @GC_malloc(i64)).
        LLVMTypeRef retType = v.utils.ptrTypeRef(LLVMInt8TypeInContext(v.context));
        LLVMTypeRef sizeType = v.utils.llvmPtrSizedIntType();
        LLVMTypeRef funcType = v.utils.functionType(retType, sizeType);
        LLVMAddFunction(v.mod, Constants.CALLOC, funcType);

        // Add array type to the current module.
        v.utils.setupArrayType();

        return v;
    }

    @Override
    public Node translatePseudoLLVM(LLVMTranslator v) {

        // Call an entry point within the current module if possible.
        List<LLVMValueRef> entryPoints = v.getEntryPoints();
        if (!entryPoints.isEmpty()) {
            buidEntryPoint(v, entryPoints.iterator().next());
        }

        // Build ctor functions, if any.
        buildCtors(v);

        return super.translatePseudoLLVM(v);
    }

    /**
     * Build a trampoline between the LLVM entry point and the Java entry point.
     */
    private static void buidEntryPoint(LLVMTranslator v, LLVMValueRef javaEntryPoint) {
        TypeSystem ts = v.typeSystem();
        LLVMTypeRef argType = v.utils.typeRef(ts.arrayOf(ts.String()));
        LLVMTypeRef funcType = v.utils.functionType(LLVMVoidTypeInContext(v.context), argType);

        LLVMValueRef func = LLVMAddFunction(v.mod, Constants.ENTRY_TRAMPOLINE, funcType);

        LLVMMetadataRef[] formals = Stream.of(ts.arrayOf(ts.String())).map(v.debugInfo::debugType).toArray(LLVMMetadataRef[]::new);
        LLVMMetadataRef typeArray = LLVMDIBuilderGetOrCreateTypeArray(v.debugInfo.diBuilder, new PointerPointer<>(formals), formals.length);
        LLVMMetadataRef funcDiType = LLVMDIBuilderCreateSubroutineType(v.debugInfo.diBuilder, v.debugInfo.createFile(), typeArray);
        v.debugInfo.funcDebugInfo(0, Constants.ENTRY_TRAMPOLINE, Constants.ENTRY_TRAMPOLINE, funcDiType, func);

        LLVMBasicBlockRef block = LLVMAppendBasicBlockInContext(v.context, func, "body");
        LLVMPositionBuilderAtEnd(v.builder, block);
        v.debugInfo.emitLocation();

        v.utils.buildProcedureCall(javaEntryPoint, LLVMGetFirstParam(func));
        LLVMBuildRetVoid(v.builder);
        v.debugInfo.popScope();
    }

    /**
     * Build ctor functions using the ctor suppliers added to the visitor during translation.
     */
    private static void buildCtors(LLVMTranslator v) {
        List<Supplier<LLVMValueRef>> ctors = v.getCtors();
        if (ctors.isEmpty())
            return;

        // Create the ctor global array as specified in the LLVM Language Reference Manual.
        LLVMTypeRef funcType = v.utils.functionType(LLVMVoidTypeInContext(v.context));
        LLVMTypeRef funcPtrType = v.utils.ptrTypeRef(funcType);
        LLVMTypeRef voidPtr = v.utils.ptrTypeRef(LLVMInt8TypeInContext(v.context));
        LLVMTypeRef structType = v.utils.structType(LLVMInt32TypeInContext(v.context), funcPtrType, voidPtr);
        LLVMTypeRef ctorVarType = LLVMArrayType(structType, /*size*/ ctors.size());
        String ctorVarName = "llvm.global_ctors";
        LLVMValueRef ctorGlobal = v.utils.getGlobal(v.mod, ctorVarName, ctorVarType);
        LLVMSetLinkage(ctorGlobal, LLVMAppendingLinkage);

        // For each ctor function, create a struct containing a priority, a pointer to the
        // ctor function, and a pointer to associated data if applicable.
        LLVMValueRef[] structs = new LLVMValueRef[ctors.size()];
        int counter = 0;
        for (Supplier<LLVMValueRef> ctor : ctors) {
            LLVMValueRef func = v.utils.getFunction(v.mod, "ctor" + counter, funcType);
            LLVMSetLinkage(func, LLVMPrivateLinkage);
            LLVMMetadataRef typeArray = LLVMDIBuilderGetOrCreateTypeArray(
                    v.debugInfo.diBuilder, new PointerPointer<>(), /*length*/ 0);
            LLVMMetadataRef funcDiType = LLVMDIBuilderCreateSubroutineType(
                    v.debugInfo.diBuilder, v.debugInfo.createFile(), typeArray);
            v.debugInfo.funcDebugInfo(0, ctorVarName, ctorVarName, funcDiType, func);

            LLVMBasicBlockRef body = LLVMAppendBasicBlockInContext(v.context, func, "body");
            LLVMPositionBuilderAtEnd(v.builder, body);

            // We use `counter` as the ctor priority to help ensure that static initializers
            // are executed in textual order, per the JLS.
            LLVMValueRef priority = LLVMConstInt(LLVMInt32TypeInContext(v.context), counter, /*sign-extend*/ 0);
            LLVMValueRef data = ctor.get(); // Calls supplier lambda to build ctor body.
            if (data == null)
                data = LLVMConstNull(voidPtr);
            LLVMValueRef castData = LLVMConstBitCast(data, voidPtr);
            structs[counter] = v.utils.buildConstStruct(priority, func, castData);

            LLVMBuildRetVoid(v.builder);
            v.debugInfo.popScope();

            ++counter;
        }

        LLVMValueRef arr = v.utils.buildConstArray(structType, structs);
        LLVMSetInitializer(ctorGlobal, arr);
    }
}
