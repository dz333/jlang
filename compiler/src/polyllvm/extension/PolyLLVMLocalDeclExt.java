package polyllvm.extension;

import polyglot.ast.LocalDecl;
import polyglot.ast.Node;
import polyglot.util.SerialVersionUID;
import polyllvm.ast.PolyLLVMExt;
import polyllvm.visit.LLVMTranslator;

import static org.bytedeco.javacpp.LLVM.*;

public class PolyLLVMLocalDeclExt extends PolyLLVMExt {
    private static final long serialVersionUID = SerialVersionUID.generate();

    @Override
    public Node leaveTranslateLLVM(LLVMTranslator v) {
        LocalDecl n = (LocalDecl) node();

        LLVMBasicBlockRef currentBlock = LLVMGetInsertBlock(v.builder);

        LLVMBasicBlockRef firstBlock = LLVMGetFirstBasicBlock(v.currFn());
        LLVMPositionBuilderBefore(v.builder,LLVMGetBasicBlockTerminator(firstBlock));
        v.debugInfo.emitLocation(n);
        LLVMValueRef alloc = LLVMBuildAlloca(v.builder, v.utils.toLL(n.type().type()), n.name());
        v.addAllocation(n.name(), alloc);

        v.debugInfo.createLocalVariable(v, n ,alloc);

        LLVMPositionBuilderAtEnd(v.builder, currentBlock);

        if (n.init() == null) {
            return super.leaveTranslateLLVM(v);
        }

        v.debugInfo.emitLocation(n);
        v.addTranslation(n, LLVMBuildStore(v.builder, v.getTranslation(n.init()), alloc));
        return super.leaveTranslateLLVM(v);
    }

    /**
     * Create a new local without debug symbols, which is not added to the map of locals
     */
    public static LLVMValueRef createLocal(LLVMTranslator v, String name, LLVMTypeRef type) {
        LLVMBasicBlockRef currentBlock = LLVMGetInsertBlock(v.builder);
        LLVMBasicBlockRef firstBlock = LLVMGetFirstBasicBlock(v.currFn());
        LLVMPositionBuilderBefore(v.builder,LLVMGetBasicBlockTerminator(firstBlock));
        LLVMValueRef alloc = LLVMBuildAlloca(v.builder, type, name);
        LLVMPositionBuilderAtEnd(v.builder, currentBlock);
        return alloc;
    }
}
