/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 * Copyright IBM Corp. 2024, 2025
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.quartz.impl;

import java.util.concurrent.atomic.AtomicInteger;


import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassWriter;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.quartz.SchedulerException;
import org.quartz.simpl.SimpleThreadPool;
import org.quartz.spi.JobStore;
import org.quartz.spi.ThreadPool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class SchedulerDetailsSetterTest  {
    @Test
    void testSetter() throws SchedulerException {
        JobStore jobStore = mock(JobStore.class);
        StdSchedulerFactory factory = StdSchedulerFactory.builder()
                .threadPool(new MyThreadPool())
                .jobStore(jobStore)
                .build();
        factory.getScheduler().shutdown(); // this will initialize all the test fixtures.

        // the thread pool is told once, the store twice (before and after it is initialized)
        assertEquals(1, instanceIdCalls.get());
        assertEquals(1, instanceNameCalls.get());
        verify(jobStore, times(2)).setInstanceId("NON_CLUSTERED");
        verify(jobStore, times(2)).setInstanceName("DefaultQuartzScheduler");
    }
    @Test
    void testMissingSetterMethods() throws SchedulerException  {
        SchedulerDetailsSetter.setDetails(new Object(), "name", "id");
    }
    @Test
    void testUnimplementedMethods() throws Exception {
        ThreadPool tp = makeIncompleteThreadPool();
        try {
            tp.setInstanceName("name");
            fail();
        } catch (AbstractMethodError ame) {
            // expected
        }

        SchedulerDetailsSetter.setDetails(tp, "name", "id");
    }


    private ThreadPool makeIncompleteThreadPool() throws InstantiationException, IllegalAccessException {
        String name = "IncompleteThreadPool";
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", new String[] { "org/quartz/spi/ThreadPool" });

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        cw.visitEnd();

        return (ThreadPool) new ClassLoader() {
            Class<?> defineClass(String clname, byte[] b) {
                return defineClass(clname, b, 0, b.length);
            }
        }.defineClass(name, cw.toByteArray()).newInstance();
    }

    private static final AtomicInteger instanceIdCalls = new AtomicInteger();
    private static final AtomicInteger instanceNameCalls = new AtomicInteger();

    public static class MyThreadPool extends SimpleThreadPool {
        
        @Override
        public void initialize() {
        }

        @Override
        public void setInstanceId(String schedInstId) {
            super.setInstanceId(schedInstId);
            instanceIdCalls.incrementAndGet();
        }

        @Override
        public void setInstanceName(String schedName) {
            super.setInstanceName(schedName);
            instanceNameCalls.incrementAndGet();
        }
    }

}
