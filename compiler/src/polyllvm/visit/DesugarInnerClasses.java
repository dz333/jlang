package polyllvm.visit;

import polyglot.ast.*;
import polyglot.frontend.ExtensionInfo;
import polyglot.frontend.Job;
import polyglot.frontend.Pass;
import polyglot.frontend.goals.AbstractGoal;
import polyglot.frontend.goals.Goal;
import polyglot.frontend.goals.VisitorGoal;
import polyglot.types.*;
import polyglot.util.Position;
import polyllvm.ast.PolyLLVMNodeFactory;
import polyllvm.types.PolyLLVMTypeSystem;
import polyllvm.util.MultiGoal;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static polyllvm.visit.DeclareEnclosingInstances.ENCLOSING_STR;

/**
 * Converts qualified this expressions within inner classes into field accesses on
 * an enclosing instance field. This occurs in two passes:
 *
 * (1) The {@link DeclareEnclosingInstances} visitor creates fields to hold immediately
 *     enclosing instances of inner classes, and prepends constructor parameters to initialize them.
 *
 * (2) The {@link SubstituteEnclosingInstances} visitor updates super constructor calls, {@code new}
 *     expressions, and qualified {@code this} expressions use enclosing instances.
 *
 * These passes cannot be combined because a {@code new} expression might reference a class before
 * it is visited, but we cannot update the constructor instance of the {@code new} expression
 * until we visit and update the constructors of the instantiated class.
 */
public class DesugarInnerClasses extends AbstractGoal {
    private final Goal declare, substitute;

    public DesugarInnerClasses(Job job, PolyLLVMTypeSystem ts, PolyLLVMNodeFactory nf) {
        super(job, "Desugar local classes");
        declare = new VisitorGoal(job, new DeclareEnclosingInstances(job, ts, nf));
        substitute = new VisitorGoal(job, new SubstituteEnclosingInstances(job, ts, nf));
    }

    @Override
    public Pass createPass(ExtensionInfo extInfo) {
        return new MultiGoal(job, declare, substitute).createPass(job.extensionInfo());
    }
}

/**
 * For each inner class, creates and initializes a field to
 * hold its immediately enclosing instance.
 */
class DeclareEnclosingInstances extends DesugarVisitor {
    static final String ENCLOSING_STR = "enclosing$";

    DeclareEnclosingInstances(Job job, PolyLLVMTypeSystem ts, PolyLLVMNodeFactory nf) {
        super(job, ts, nf);
    }

    @Override
    public ClassBody leaveClassBody(ParsedClassType ct, ClassBody cb) {

        if (ct.isInnerClass() && ct.hasEnclosingInstance(ct.outer())) {
            FieldDecl field = tnf.FieldDecl(
                    cb.position(), ENCLOSING_STR, ct.outer(),
                    ct, /*init*/ null, Flags.FINAL);
            // Most of the rewriting happens in this helper function.
            cb = prependConstructorInitializedFields(ct, cb, Collections.singletonList(field));
        }

        return super.leaveClassBody(ct, cb);
    }
}

/**
 * Updates super constructor calls and {@code new} expressions to pass in the immediately
 * enclosing instance of the constructor container, and converts qualified {@code this}
 * expressions to field accesses through enclosing instances.
 */
class SubstituteEnclosingInstances extends DesugarVisitor {

    // The translation proceeds as follows. See JLS 7th Ed. 8.1.3 for terminology.
    // - Update super constructor calls and {@code new} expressions.
    //   - If qualified: use value of qualifier.
    //   - If unqualified: get the enclosing instance with the same type as the
    //     immediately enclosing instance of the constructor instance container.
    // - Translate {@code this} expressions to go through enclosing instance fields
    //   when necessary. When inside constructors, be careful to avoid using the enclosing
    //   instance field before it's initialized.

    SubstituteEnclosingInstances(Job job, PolyLLVMTypeSystem ts, PolyLLVMNodeFactory nf) {
        super(job, ts, nf);
    }

    /** Given an expression, returns its enclosing instance of the specified type. */
    private Expr getEnclosingInstance(Expr expr, ClassType targetType) {
        if (expr.type().typeEquals(targetType))
            return expr;
        Field enclosing = tnf.Field(expr.position(), expr, ENCLOSING_STR);
        return getEnclosingInstance(enclosing, targetType);
    }

    /** Return the enclosing instance of the specified type with respect to the current class. */
    private Expr getEnclosingInstance(Position pos, ClassType targetType) {
        ClassType currClass = classes.peek();

        // If we are inside a constructor, try to use an enclosing instance formal rather than the
        // enclosing instance field. This ensures that enclosing instance fields are not accessed
        // in the constructor before they are initialized.
        if (!currClass.typeEquals(targetType) && !constructors.isEmpty()) {
            ConstructorDecl ctor = constructors.peek();
            if (ctor.constructorInstance().container().typeEquals(currClass)) {
                List<Formal> enclosingFormals = ctor.formals().stream()
                        .filter((f) -> f.name().equals(ENCLOSING_STR))
                        .collect(Collectors.toList());
                assert enclosingFormals.size() == 1;
                Formal enclosingFormal = enclosingFormals.get(0);
                Local enclosing = tnf.Local(enclosingFormal.position(), enclosingFormal);
                return getEnclosingInstance(enclosing, targetType);
            }
        }

        // Otherwise, look for an enclosing instance through enclosing instance fields.
        Special unqualified = tnf.UnqualifiedThis(pos, currClass);
        return getEnclosingInstance(unqualified, targetType);
    }

    @Override
    public Node leaveDesugar(Node n) throws SemanticException {

        // Pass enclosing instance to {@code new} expressions.
        if (n instanceof New) {
            New nw = (New) n;
            Type container = nw.constructorInstance().container();
            if (container.isClass() && container.toClass().isInnerClass()) {
                ClassType outer = container.toClass().outer();
                if (container.toClass().hasEnclosingInstance(outer)) {
                    Position pos = nw.position();
                    Expr enclosing = nw.qualifier() != null
                            ? nw.qualifier()
                            : getEnclosingInstance(pos, outer);
                    List<Expr> args = concat(enclosing, nw.arguments());
                    n = tnf.New(pos, nw.type().toClass(), /*outer*/ null, args, nw.body());
                }
            }
        }

        // Pass enclosing instance to super constructor calls.
        if (n instanceof ConstructorCall) {
            ConstructorCall cc = (ConstructorCall) n;
            ClassType container = cc.constructorInstance().container().toClass();
            if (cc.kind().equals(ConstructorCall.SUPER) && container.isInnerClass()) {
                if (container.hasEnclosingInstance(container.outer())) {
                    Expr enclosing = cc.qualifier() != null
                            ? cc.qualifier()
                            : getEnclosingInstance(cc.position(), container.outer());
                    List<Expr> args = concat(enclosing, cc.arguments());
                    n = tnf.ConstructorCall(cc.position(), cc.kind(), container, args);
                }
            }
        }

        // Convert qualified {@code this} and {@code super} to enclosing instance accesses.
        if (n instanceof Special) {
            Special s = (Special) n;
            ClassType enclosingType = s.qualifier() != null
                    ? s.qualifier().type().toClass()
                    : classes.peek();
            Expr res = getEnclosingInstance(s.position(), enclosingType);
            if (s.kind().equals(Special.SUPER))
                res = tnf.Cast(n.position(), res.type().toClass().superType(), res);
            n = res;
        }

        return super.leaveDesugar(n);
    }
}
