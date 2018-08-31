/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.api.tasks

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.workers.IsolationMode
import spock.lang.Unroll

class TaskTimeoutIntegrationTest extends AbstractIntegrationSpec {
    def "timeout stops long running method call"() {
        given:
        buildFile << """
            task block() {
                doLast {
                    Thread.sleep(60000)
                }
                timeoutAfter 200, java.util.concurrent.TimeUnit.MILLISECONDS
            }
            """

        expect:
        fails"block"
        failure.assertHasDescription("task ':block' exceeded its timeout")
    }

    def "other tasks still run after a timeout if --continue is used"() {
        given:
        buildFile << """
            task block() {
                doLast {
                    Thread.sleep(60000)
                }
                timeoutAfter 200, java.util.concurrent.TimeUnit.MILLISECONDS
            }
            
            task foo() {
            }
            """

        expect:
        fails"block", "foo", "--continue"
        result.assertTaskExecuted(":foo")
        failure.assertHasDescription("task ':block' exceeded its timeout")
    }

    def "timeout stops long running exec()"() {
        given:
        file('src/main/java/Block.java') << """ 
            import java.util.concurrent.CountDownLatch;

            public class Block {
                public static void main(String[] args) throws InterruptedException {
                    new CountDownLatch(1).await();
                }
            }
        """
        buildFile << """
            apply plugin: 'java'
            task block(type: JavaExec) {
                classpath = sourceSets.main.output
                main = 'Block'
                timeoutAfter 200, java.util.concurrent.TimeUnit.MILLISECONDS
            }
            """

        expect:
        fails"block"
        failure.assertHasDescription("task ':block' exceeded its timeout")
    }

    def "timeout stops long running test"() {
        given:
        file('src/test/java/Block.java') << """ 
            import java.util.concurrent.CountDownLatch;
            import org.junit.Test;

            public class Block {
                @Test
                public void test() throws InterruptedException {
                    new CountDownLatch(1).await();
                }
            }
        """
        buildFile << """
            apply plugin: 'java'
            ${jcenterRepository()}
            dependencies {
                testImplementation 'junit:junit:4.12'
            }
            test.timeoutAfter 200, java.util.concurrent.TimeUnit.MILLISECONDS
            """

        expect:
        fails"test"
        failure.assertHasDescription("task ':test' exceeded its timeout")
    }

    @Unroll
    def "timeout stops long running work item with #isolationMode isolation"() {
        given:
        file('src/test/java/Block.java') << """ 
            import java.util.concurrent.CountDownLatch;
            import org.junit.Test;

            public class Block {
                @Test
                public void test() throws InterruptedException {
                    new CountDownLatch(1).await();
                }
            }
        """
        buildFile << """
            import java.util.concurrent.CountDownLatch;
            import javax.inject.Inject;
            
            task block(type: WorkerTask) {
                timeoutAfter 500, java.util.concurrent.TimeUnit.MILLISECONDS
            }
            
            class WorkerTask extends DefaultTask {

                @Inject
                WorkerExecutor getWorkerExecutor() {
                    throw new UnsupportedOperationException()
                }

                @TaskAction
                void executeTask() {
                    workerExecutor.submit(BlockingRunnable) {
                        isolationMode = IsolationMode.$isolationMode
                    }
                }
            }

            public class BlockingRunnable implements Runnable {
                @Inject
                public BlockingRunnable() {
                }

                public void run() {
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException e ) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            """

        expect:
        fails"block"
        failure.assertHasDescription("task ':block' exceeded its timeout")

        where:
        isolationMode << IsolationMode.values()
    }
}
