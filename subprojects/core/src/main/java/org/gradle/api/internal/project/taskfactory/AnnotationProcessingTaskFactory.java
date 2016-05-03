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

import com.google.common.collect.Maps;
import org.gradle.api.*;
import org.gradle.api.internal.AbstractTask;
import org.gradle.api.internal.ConventionTask;
import org.gradle.api.internal.DynamicObjectUtil;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.changedetection.TaskArtifactState;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.tasks.ContextAwareTaskAction;
import org.gradle.api.internal.tasks.TaskExecutionContext;
import org.gradle.api.internal.TaskOutputsInternal;
import org.gradle.api.internal.tasks.execution.TaskValidator;
import org.gradle.api.specs.Spec;
import org.gradle.api.specs.Specs;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.incremental.IncrementalTaskInputs;
import org.gradle.internal.Factory;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.reflect.JavaReflectionUtil;
import org.gradle.util.DeprecationLogger;

import java.beans.Introspector;
import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * A {@link ITaskFactory} which determines task actions, inputs and outputs based on annotation attached to the task properties. Also provides some validation based on these annotations.
 */
public class AnnotationProcessingTaskFactory implements ITaskFactory {
    private final ITaskFactory taskFactory;
    private final Map<Class, TaskClassInfo> classInfos;

    private final static Transformer<Iterable<File>, Object> FILE_PROPERTY_TRANSFORMER = new Transformer<Iterable<File>, Object>() {
        public Iterable<File> transform(Object original) {
            File file = (File) original;
            return file == null ? Collections.<File>emptyList() : Collections.singleton(file);
        }
    };

    private final static Transformer<Iterable<File>, Object> ITERABLE_FILE_PROPERTY_TRANSFORMER = new Transformer<Iterable<File>, Object>() {
        @SuppressWarnings("unchecked")
        public Iterable<File> transform(Object original) {
            return original != null ? (Iterable<File>) original : Collections.<File>emptyList();
        }
    };

    private static final TaskOutputExtractor<OutputFile> OUTPUT_FILE_EXTRACTOR = new TaskOutputExtractor<OutputFile>() {
        @Override
        public void extractOutput(String propertyName, Callable<Object> futureValue, TaskOutputsInternal outputs) {
            outputs.includeFile(propertyName, futureValue);
        }
    };

    private static final TaskOutputExtractor<OutputFiles> OUTPUT_FILES_EXTRACTOR = new TaskOutputExtractor<OutputFiles>() {
        @Override
        public void extractOutput(String propertyName, Callable<Object> futureValue, TaskOutputsInternal outputs) {
            outputs.includeFiles(propertyName, futureValue);
        }
    };

    private static final TaskOutputExtractor<OutputDirectory> OUTPUT_DIRECTORY_EXTRACTOR = new TaskOutputExtractor<OutputDirectory>() {
        @Override
        public void extractOutput(String propertyName, Callable<Object> futureValue, TaskOutputsInternal outputs) {
            outputs.includeDir(propertyName, futureValue);
        }
    };

    private static final TaskOutputExtractor<OutputDirectories> OUTPUT_DIRECTORIES_EXTRACTOR = new TaskOutputExtractor<OutputDirectories>() {
        @Override
        public void extractOutput(String propertyName, Callable<Object> futureValue, TaskOutputsInternal outputs) {
            outputs.includeFiles(propertyName, futureValue);
        }
    };

    private final static List<? extends PropertyAnnotationHandler> HANDLERS = Arrays.asList(
            new InputFilePropertyAnnotationHandler(),
            new InputDirectoryPropertyAnnotationHandler(),
            new InputFilesPropertyAnnotationHandler(),
            new OutputFilePropertyAnnotationHandler<OutputFile>(OutputFile.class, FILE_PROPERTY_TRANSFORMER, OUTPUT_FILE_EXTRACTOR),
            new OutputFilePropertyAnnotationHandler<OutputFiles>(OutputFiles.class, ITERABLE_FILE_PROPERTY_TRANSFORMER, OUTPUT_FILES_EXTRACTOR),
            new OutputDirectoryPropertyAnnotationHandler<OutputDirectory>(OutputDirectory.class, FILE_PROPERTY_TRANSFORMER, OUTPUT_DIRECTORY_EXTRACTOR),
            new OutputDirectoryPropertyAnnotationHandler<OutputDirectories>(OutputDirectories.class, ITERABLE_FILE_PROPERTY_TRANSFORMER, OUTPUT_DIRECTORIES_EXTRACTOR),
            new InputPropertyAnnotationHandler(),
            new NestedBeanPropertyAnnotationHandler());

    private final static ValidationAction NOT_NULL_VALIDATOR = new ValidationAction() {
        public void validate(String propertyName, Object value, Collection<String> messages) {
            if (value == null) {
                messages.add(String.format("No value has been specified for property '%s'.", propertyName));
            }
        }
    };

    public AnnotationProcessingTaskFactory(ITaskFactory taskFactory) {
        this.taskFactory = taskFactory;
        this.classInfos = new HashMap<Class, TaskClassInfo>();
    }

    private AnnotationProcessingTaskFactory(Map<Class, TaskClassInfo> classInfos, ITaskFactory taskFactory) {
        this.classInfos = classInfos;
        this.taskFactory = taskFactory;
    }

    public ITaskFactory createChild(ProjectInternal project, Instantiator instantiator) {
        return new AnnotationProcessingTaskFactory(classInfos, taskFactory.createChild(project, instantiator));
    }

    public TaskInternal createTask(Map<String, ?> args) {
        return process(taskFactory.createTask(args));
    }

    @Override
    public <S extends TaskInternal> S create(String name, Class<S> type) {
        return process(taskFactory.create(name, type));
    }

    private <S extends TaskInternal> S process(S task) {
        TaskClassInfo taskClassInfo = getTaskClassInfo(task.getClass());

        if (taskClassInfo.incremental) {
            // Add a dummy upToDateWhen spec: this will force TaskOutputs.hasOutputs() to be true.
            task.getOutputs().upToDateWhen(new Spec<Task>() {
                public boolean isSatisfiedBy(Task element) {
                    return true;
                }
            });
        }
        if (taskClassInfo.cacheable) {
            task.getOutputs().cacheIf(Specs.SATISFIES_ALL);
        }

        for (Factory<Action<Task>> actionFactory : taskClassInfo.taskActions) {
            task.prependParallelSafeAction(actionFactory.create());
        }

        if (taskClassInfo.validator != null) {
            task.prependParallelSafeAction(taskClassInfo.validator);
            taskClassInfo.validator.addInputsAndOutputs(task);
        }

        return task;
    }

    private TaskClassInfo getTaskClassInfo(Class<? extends Task> type) {
        TaskClassInfo taskClassInfo = classInfos.get(type);
        if (taskClassInfo == null) {
            taskClassInfo = new TaskClassInfo();
            findTaskActions(type, taskClassInfo);

            Validator validator = new Validator();
            validator.attachActions(null, type);

            if (!validator.properties.isEmpty()) {
                taskClassInfo.validator = validator;
            }

            Class<?> declaredType = DynamicObjectUtil.getDeclaredType(type);
            taskClassInfo.cacheable = declaredType.isAnnotationPresent(CacheableTask.class);

            classInfos.put(type, taskClassInfo);
        }
        return taskClassInfo;
    }

    private void findTaskActions(Class<? extends Task> type, TaskClassInfo taskClassInfo) {
        Set<String> methods = new HashSet<String>();
        for (Class current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                attachTaskAction(method, taskClassInfo, methods);
            }
        }
    }

    private void attachTaskAction(final Method method, TaskClassInfo taskClassInfo, Collection<String> processedMethods) {
        if (method.getAnnotation(TaskAction.class) == null) {
            return;
        }
        if (Modifier.isStatic(method.getModifiers())) {
            throw new GradleException(String.format("Cannot use @TaskAction annotation on static method %s.%s().",
                    method.getDeclaringClass().getSimpleName(), method.getName()));
        }
        final Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length > 1) {
            throw new GradleException(String.format(
                    "Cannot use @TaskAction annotation on method %s.%s() as this method takes multiple parameters.",
                    method.getDeclaringClass().getSimpleName(), method.getName()));
        }

        if (parameterTypes.length == 1) {
            if (!parameterTypes[0].equals(IncrementalTaskInputs.class)) {
                throw new GradleException(String.format(
                        "Cannot use @TaskAction annotation on method %s.%s() because %s is not a valid parameter to an action method.",
                        method.getDeclaringClass().getSimpleName(), method.getName(), parameterTypes[0]));
            }
            if (taskClassInfo.incremental) {
                throw new GradleException(String.format("Cannot have multiple @TaskAction methods accepting an %s parameter.", IncrementalTaskInputs.class.getSimpleName()));
            }
            taskClassInfo.incremental = true;
        }
        if (processedMethods.contains(method.getName())) {
            return;
        }
        taskClassInfo.taskActions.add(createActionFactory(method, parameterTypes));
        processedMethods.add(method.getName());
    }

    private Factory<Action<Task>> createActionFactory(final Method method, final Class<?>[] parameterTypes) {
        return new Factory<Action<Task>>() {
            public Action<Task> create() {
                if (parameterTypes.length == 1) {
                    return new IncrementalTaskAction(method);
                } else {
                    return new StandardTaskAction(method);
                }
            }
        };
    }

    private static boolean isGetter(Method method) {
        return ((method.getName().startsWith("get") && method.getReturnType() != Void.TYPE)
                || (method.getName().startsWith("is") && method.getReturnType().equals(boolean.class)))
                && method.getParameterTypes().length == 0 && !Modifier.isStatic(method.getModifiers());
    }

    private static class StandardTaskAction implements Action<Task> {
        private final Method method;

        public StandardTaskAction(Method method) {
            this.method = method;
        }

        public void execute(Task task) {
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(method.getDeclaringClass().getClassLoader());
            try {
                doExecute(task, method.getName());
            } finally {
                Thread.currentThread().setContextClassLoader(original);
            }
        }

        protected void doExecute(Task task, String methodName) {
            JavaReflectionUtil.method(task, Object.class, methodName).invoke(task);
        }
    }

    public static class IncrementalTaskAction extends StandardTaskAction implements ContextAwareTaskAction {

        private TaskArtifactState taskArtifactState;

        public IncrementalTaskAction(Method method) {
            super(method);
        }

        public void contextualise(TaskExecutionContext context) {
            this.taskArtifactState = context == null ? null : context.getTaskArtifactState();
        }

        protected void doExecute(Task task, String methodName) {
            JavaReflectionUtil.method(task, Object.class, methodName, IncrementalTaskInputs.class).invoke(task, taskArtifactState.getInputChanges());
            taskArtifactState = null;
        }
    }

    private static class TaskClassInfo {
        public Validator validator;
        public List<Factory<Action<Task>>> taskActions = new ArrayList<Factory<Action<Task>>>();
        public boolean incremental;
        public boolean cacheable;
    }

    private static class Validator implements Action<Task>, TaskValidator {
        private Set<PropertyInfo> properties = new LinkedHashSet<PropertyInfo>();

        public void addInputsAndOutputs(final TaskInternal task) {
            task.addValidator(this);
            for (final PropertyInfo property : properties) {
                Callable<Object> futureValue = new Callable<Object>() {
                    public Object call() throws Exception {
                        return property.getValue(task).getValue();
                    }
                };

                property.configureAction.update(task, futureValue);
            }
        }

        public void execute(Task task) {
        }

        public void validate(TaskInternal task, Collection<String> messages) {
            List<PropertyValue> propertyValues = new ArrayList<PropertyValue>();
            for (PropertyInfo property : properties) {
                propertyValues.add(property.getValue(task));
            }
            for (PropertyValue propertyValue : propertyValues) {
                propertyValue.checkNotNull(messages);
            }
            for (PropertyValue propertyValue : propertyValues) {
                propertyValue.checkValid(messages);
            }
        }

        public void attachActions(PropertyInfo parent, Class<?> type) {
            Class<?> superclass = type.getSuperclass();
            if (!(superclass == null
                    // Avoid reflecting on classes we know we don't need to look at
                    || superclass.equals(ConventionTask.class) || superclass.equals(DefaultTask.class)
                    || superclass.equals(AbstractTask.class) || superclass.equals(Object.class)
            )) {
                attachActions(parent, superclass);
            }

            Map<String, Field> fields = getFields(type);
            for (Method method : type.getDeclaredMethods()) {
                if (!isGetter(method) || method.isBridge()) {
                    continue;
                }

                String name = method.getName();
                int prefixLength = name.startsWith("is") ? 2 : 3; // it's 'get' if not 'is'.
                String fieldName = Introspector.decapitalize(name.substring(prefixLength));
                String propertyName = fieldName;
                if (parent != null) {
                    propertyName = parent.getName() + '.' + propertyName;
                }
                Field field = fields.get(fieldName);

                PropertyInfo propertyInfo = new PropertyInfo(this, parent, propertyName, method, field);

                attachValidationActions(propertyInfo);

                if (propertyInfo.required) {
                    properties.add(propertyInfo);
                }
            }
        }

        private Map<String, Field> getFields(Class<?> type) {
            Map<String, Field> fields = Maps.newHashMap();
            for (Field field : type.getDeclaredFields()) {
                fields.put(field.getName(), field);
            }
            return fields;
        }

        private void attachValidationActions(PropertyInfo propertyInfo) {
            for (PropertyAnnotationHandler handler : HANDLERS) {
                attachValidationAction(handler, propertyInfo);
            }
        }

        private void attachValidationAction(PropertyAnnotationHandler handler, PropertyInfo propertyInfo) {
            Class<? extends Annotation> annotationType = handler.getAnnotationType();

            AnnotatedElement annotationTarget = propertyInfo.getAnnotatedElement(annotationType);
            if (annotationTarget == null) {
                return;
            }

            Annotation optional = annotationTarget.getAnnotation(org.gradle.api.tasks.Optional.class);
            if (optional == null) {
                propertyInfo.setNotNullValidator(NOT_NULL_VALIDATOR);
            }

            propertyInfo.attachActions(handler);
        }
    }

    private interface PropertyValue {
        Object getValue();

        void checkNotNull(Collection<String> messages);

        void checkValid(Collection<String> messages);
    }

    private static class PropertyInfo implements PropertyActionContext {
        private static final ValidationAction NO_OP_VALIDATION_ACTION = new ValidationAction() {
            public void validate(String propertyName, Object value, Collection<String> messages) {
            }
        };
        private static final PropertyValue NO_OP_VALUE = new PropertyValue() {
            public Object getValue() {
                return null;
            }

            public void checkNotNull(Collection<String> messages) {
            }

            public void checkValid(Collection<String> messages) {
            }
        };
        private static final UpdateAction NO_OP_CONFIGURATION_ACTION = new UpdateAction() {
            public void update(TaskInternal task, Callable<Object> futureValue) {
            }
        };

        private final Validator validator;
        private final PropertyInfo parent;
        private final String propertyName;
        private final Method method;
        private ValidationAction validationAction = NO_OP_VALIDATION_ACTION;
        private ValidationAction notNullValidator = NO_OP_VALIDATION_ACTION;
        private UpdateAction configureAction = NO_OP_CONFIGURATION_ACTION;
        public boolean required;
        private final Field instanceVariableField;

        private PropertyInfo(Validator validator, PropertyInfo parent, String propertyName, Method method, Field instanceVariableField) {
            this.validator = validator;
            this.parent = parent;
            this.propertyName = propertyName;
            this.method = method;
            this.instanceVariableField = instanceVariableField;
        }

        @Override
        public String toString() {
            return propertyName;
        }

        public String getName() {
            return propertyName;
        }

        public Class<?> getType() {
            return method.getReturnType();
        }

        public Class<?> getInstanceVariableType() {
            return instanceVariableField != null ? instanceVariableField.getType() : null;
        }

        @Override
        public AnnotatedElement getAnnotatedElement(Class<? extends Annotation> annotationType) {
            if (method.isAnnotationPresent(annotationType)) {
                return method;
            } else if (instanceVariableField != null && instanceVariableField.isAnnotationPresent(annotationType)) {
                return instanceVariableField;
            } else {
                return null;
            }
        }

        @Override
        public <A extends Annotation> A getAnnotation(Class<A> annotationType) {
            A annotation = method.getAnnotation(annotationType);
            if (annotation == null && instanceVariableField != null) {
                annotation = instanceVariableField.getAnnotation(annotationType);
            }
            return annotation;
        }

        public void setValidationAction(ValidationAction action) {
            validationAction = action;
        }

        public void setConfigureAction(UpdateAction action) {
            configureAction = action;
        }

        public void setNotNullValidator(ValidationAction notNullValidator) {
            this.notNullValidator = notNullValidator;
        }

        public void attachActions(Class<?> type) {
            validator.attachActions(this, type);
        }

        public PropertyValue getValue(Object rootObject) {
            Object bean = rootObject;
            if (parent != null) {
                PropertyValue parentValue = parent.getValue(rootObject);
                if (parentValue.getValue() == null) {
                    return NO_OP_VALUE;
                }
                bean = parentValue.getValue();
            }

            final Object finalBean = bean;
            final Object value = DeprecationLogger.whileDisabled(new Factory<Object>() {
                public Object create() {
                    return JavaReflectionUtil.method(finalBean, Object.class, method).invoke(finalBean);
                }
            });

            return new PropertyValue() {
                public Object getValue() {
                    return value;
                }

                public void checkNotNull(Collection<String> messages) {
                    notNullValidator.validate(propertyName, value, messages);
                }

                public void checkValid(Collection<String> messages) {
                    if (value != null) {
                        validationAction.validate(propertyName, value, messages);
                    }
                }
            };
        }

        public void attachActions(PropertyAnnotationHandler handler) {
            handler.attachActions(this);
            required = true;
        }
    }
}