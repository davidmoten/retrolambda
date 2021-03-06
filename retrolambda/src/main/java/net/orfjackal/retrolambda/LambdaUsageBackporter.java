// Copyright © 2013-2014 Esko Luontola <www.orfjackal.net>
// This software is released under the Apache License 2.0.
// The license text is at http://www.apache.org/licenses/LICENSE-2.0

package net.orfjackal.retrolambda;

import org.objectweb.asm.*;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import static org.objectweb.asm.Opcodes.*;

public class LambdaUsageBackporter {

    public static byte[] transform(byte[] bytecode, int targetVersion) {
        resetLambdaClassSequenceNumber();
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(bytecode).accept(new MyClassVisitor(cw, targetVersion), 0);
        return cw.toByteArray();
    }

    private static void resetLambdaClassSequenceNumber() {
        try {
            Field counterField = Class.forName("java.lang.invoke.InnerClassLambdaMetafactory").getDeclaredField("counter");
            counterField.setAccessible(true);
            AtomicInteger counter = (AtomicInteger) counterField.get(null);
            counter.set(0);
        } catch (Exception e) {
            System.err.println("WARNING: Failed to start class numbering from one. Don't worry, it's cosmetic, " +
                    "but please file a bug report and tell on which JDK version this happened.");
            e.printStackTrace();
        }
    }


    private static class MyClassVisitor extends ClassVisitor {
        private final int targetVersion;
        private int classAccess;
        private String className;

        public MyClassVisitor(ClassWriter cw, int targetVersion) {
            super(ASM4, cw);
            this.targetVersion = targetVersion;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            if (version > targetVersion) {
                version = targetVersion;
            }
            super.visit(version, access, name, signature, superName, interfaces);
            this.classAccess = access;
            this.className = name;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            if (isBridgeMethodOnInterface(access)) {
                return null; // remove the bridge method; Java 7 didn't use them
            }
            if (isNonAbstractMethodOnInterface(access)) {
                // We are not aware of other reasons than the bridge methods
                // why JDK 8 would produce non-abstract methods on interfaces,
                // but we have this warning here to get a bug report sooner
                // in case we missed something.
                System.out.println("WARNING: Method '" + name + "' of interface '" + className + "' is non-abstract! " +
                        "This will probably fail to run on Java 7 and below. " +
                        "If you get this warning _without_ using Java 8's default methods, " +
                        "please report a bug at https://github.com/orfjackal/retrolambda/issues " +
                        "together with an SSCCE (http://www.sscce.org/)");
            }
            if (LambdaNaming.LAMBDA_IMPL_METHOD.matcher(name).matches()) {
                access = Flags.makeNonPrivate(access);
            }
            MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
            return new InvokeDynamicInsnConvertingMethodVisitor(api, mv, className);
        }

        private boolean isBridgeMethodOnInterface(int methodAccess) {
            return Flags.hasFlag(classAccess, Opcodes.ACC_INTERFACE) &&
                    Flags.hasFlag(methodAccess, Opcodes.ACC_BRIDGE);
        }

        private boolean isNonAbstractMethodOnInterface(int methodAccess) {
            return Flags.hasFlag(classAccess, Opcodes.ACC_INTERFACE) &&
                    !Flags.hasFlag(methodAccess, Opcodes.ACC_ABSTRACT);
        }
    }

    private static class InvokeDynamicInsnConvertingMethodVisitor extends MethodVisitor {
        private final String myClassName;

        public InvokeDynamicInsnConvertingMethodVisitor(int api, MethodVisitor mv, String myClassName) {
            super(api, mv);
            this.myClassName = myClassName;
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
            if (bsm.getOwner().equals(LambdaNaming.LAMBDA_METAFACTORY)) {
                backportLambda(name, Type.getType(desc), bsm, bsmArgs);
            } else {
                super.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs);
            }
        }

        private void backportLambda(String invokedName, Type invokedType, Handle bsm, Object[] bsmArgs) {
            Class<?> invoker = loadClass(myClassName);
            LambdaFactoryMethod factory = LambdaReifier.reifyLambdaClass(invoker, invokedName, invokedType, bsm, bsmArgs);
            super.visitMethodInsn(INVOKESTATIC, factory.getOwner(), factory.getName(), factory.getDesc());
        }

        private static Class<?> loadClass(String className) {
            try {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                return cl.loadClass(className.replace('/', '.'));
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
