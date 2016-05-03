/*
 * Copyright 2010 the original author or authors.
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
package org.gradle.api.internal.project.taskfactory;

import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.Transformer;
import org.gradle.api.internal.TaskInternal;
import org.gradle.internal.FileUtils;
import org.gradle.util.GFileUtils;

import java.io.File;
import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.concurrent.Callable;

import static org.gradle.util.GUtil.uncheckedCall;

public class OutputDirectoryPropertyAnnotationHandler<A extends Annotation> implements PropertyAnnotationHandler {

    private final Class<A> annotationType;
    private final Transformer<Iterable<File>, Object> valueTransformer;
    private final TaskOutputExtractor<A> outputExtractor;

    public OutputDirectoryPropertyAnnotationHandler(Class<A> annotationType, Transformer<Iterable<File>, Object> valueTransformer, TaskOutputExtractor<A> outputExtractor) {
        this.annotationType = annotationType;
        this.valueTransformer = valueTransformer;
        this.outputExtractor = outputExtractor;
    }

    public Class<A> getAnnotationType() {
        return annotationType;
    }

    private final ValidationAction outputDirValidation = new ValidationAction() {
        public void validate(String propertyName, Object value, Collection<String> messages) {
            for (File file : valueTransformer.transform(value)) {
                if (file.exists() && !file.isDirectory()) {
                    messages.add(String.format("Directory '%s' specified for property '%s' is not a directory.", file, propertyName));
                    return;
                }

                for (File candidate = file; candidate != null && !candidate.isDirectory(); candidate = candidate.getParentFile()) {
                    if (candidate.exists() && !candidate.isDirectory()) {
                        messages.add(String.format("Cannot write to directory '%s' specified for property '%s', as ancestor '%s' is not a directory.", file, propertyName, candidate));
                        break;
                    }
                }
            }
        }
    };

    public void attachActions(final PropertyActionContext context) {
        context.setValidationAction(outputDirValidation);
        context.setConfigureAction(new UpdateAction() {
            public void update(TaskInternal task, final Callable<Object> futureValue) {
                outputExtractor.extractOutput(context.getName(), futureValue, task.getOutputs());
                task.prependParallelSafeAction(new Action<Task>() {
                    public void execute(Task task) {
                        Iterable<File> files = valueTransformer.transform(uncheckedCall(futureValue));
                        for (File file : files) {
                            file = FileUtils.canonicalize(file);
                            GFileUtils.mkdirs(file);
                        }
                    }
                });
            }
        });
    }
}