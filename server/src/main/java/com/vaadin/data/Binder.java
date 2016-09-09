/*
 * Copyright 2000-2016 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.vaadin.data.util.converter.Converter;
import com.vaadin.data.util.converter.StringToIntegerConverter;
import com.vaadin.server.ErrorMessage;
import com.vaadin.server.UserError;
import com.vaadin.shared.Registration;
import com.vaadin.ui.AbstractComponent;
import com.vaadin.ui.Label;

/**
 * Connects one or more {@code Field} components to properties of a backing data
 * type such as a bean type. With a binder, input components can be grouped
 * together into forms to easily create and update business objects with little
 * explicit logic needed to move data between the UI and the data layers of the
 * application.
 * <p>
 * A binder is a collection of <i>bindings</i>, each representing the mapping of
 * a single field, through converters and validators, to a backing property.
 * <p>
 * A binder instance can be bound to a single bean instance at a time, but can
 * be rebound as needed. This allows usage patterns like a <i>master-details</i>
 * view, where a select component is used to pick the bean to edit.
 * <p>
 * Bean level validators can be added using the
 * {@link #withValidator(Validator)} method and will be run on the bound bean
 * once it has been updated from the values of the bound fields. Bean level
 * validators are also run as part of {@link #save(Object)} and
 * {@link #saveIfValid(Object)} if all field level validators pass.
 * <p>
 * Note: For bean level validators, the item must be updated before the
 * validators are run. If a bean level validator fails in {@link #save(Object)}
 * or {@link #saveIfValid(Object)}, the item will be reverted to the previous
 * state before returning from the method. You should ensure that the
 * getters/setters in the item do not have side effects.
 * <p>
 * Unless otherwise specified, {@code Binder} method arguments cannot be null.
 *
 * @author Vaadin Ltd.
 *
 * @param <BEAN>
 *            the bean type
 *
 * @see Binding
 * @see HasValue
 *
 * @since 8.0
 */
public class Binder<BEAN> implements Serializable {

    /**
     * Represents the binding between a field and a data property.
     *
     * @param <BEAN>
     *            the bean type
     * @param <FIELDVALUE>
     *            the value type of the field
     * @param <TARGET>
     *            the target data type of the binding, matches the field type
     *            until a converter has been set
     *
     * @see Binder#forField(HasValue)
     */
    public interface Binding<BEAN, FIELDVALUE, TARGET> extends Serializable {

        /**
         * Completes this binding using the given getter and setter functions
         * representing a backing bean property. The functions are used to
         * update the field value from the property and to store the field value
         * to the property, respectively.
         * <p>
         * When a bean is bound with {@link Binder#bind(BEAN)}, the field value
         * is set to the return value of the given getter. The property value is
         * then updated via the given setter whenever the field value changes.
         * The setter may be null; in that case the property value is never
         * updated and the binding is said to be <i>read-only</i>.
         * <p>
         * If the Binder is already bound to some item, the newly bound field is
         * associated with the corresponding bean property as described above.
         * <p>
         * The getter and setter can be arbitrary functions, for instance
         * implementing user-defined conversion or validation. However, in the
         * most basic use case you can simply pass a pair of method references
         * to this method as follows:
         *
         * <pre>
         * class Person {
         *     public String getName() { ... }
         *     public void setName(String name) { ... }
         * }
         *
         * TextField nameField = new TextField();
         * binder.forField(nameField).bind(Person::getName, Person::setName);
         * </pre>
         *
         * @param getter
         *            the function to get the value of the property to the
         *            field, not null
         * @param setter
         *            the function to save the field value to the property or
         *            null if read-only
         * @throws IllegalStateException
         *             if {@code bind} has already been called on this binding
         */
        public void bind(Function<BEAN, TARGET> getter,
                BiConsumer<BEAN, TARGET> setter);

        /**
         * Adds a validator to this binding. Validators are applied, in
         * registration order, when the field value is saved to the backing
         * property. If any validator returns a failure, the property value is
         * not updated.
         *
         * @param validator
         *            the validator to add, not null
         * @return this binding, for chaining
         * @throws IllegalStateException
         *             if {@code bind} has already been called
         */
        public Binding<BEAN, FIELDVALUE, TARGET> withValidator(
                Validator<? super TARGET> validator);

        /**
         * A convenience method to add a validator to this binding using the
         * {@link Validator#from(Predicate, String)} factory method.
         * <p>
         * Validators are applied, in registration order, when the field value
         * is saved to the backing property. If any validator returns a failure,
         * the property value is not updated.
         *
         * @see #withValidator(Validator)
         * @see Validator#from(Predicate, String)
         *
         * @param predicate
         *            the predicate performing validation, not null
         * @param message
         *            the error message to report in case validation failure
         * @return this binding, for chaining
         * @throws IllegalStateException
         *             if {@code bind} has already been called
         */
        public default Binding<BEAN, FIELDVALUE, TARGET> withValidator(
                Predicate<? super TARGET> predicate, String message) {
            return withValidator(Validator.from(predicate, message));
        }

        /**
         * Maps the binding to another data type using the given
         * {@link Converter}.
         * <p>
         * A converter is capable of converting between a presentation type,
         * which must match the current target data type of the binding, and a
         * model type, which can be any data type and becomes the new target
         * type of the binding. When invoking
         * {@link #bind(Function, BiConsumer)}, the target type of the binding
         * must match the getter/setter types.
         * <p>
         * For instance, a {@code TextField} can be bound to an integer-typed
         * property using an appropriate converter such as a
         * {@link StringToIntegerConverter}.
         *
         * @param <NEWTARGET>
         *            the type to convert to
         * @param converter
         *            the converter to use, not null
         * @return a new binding with the appropriate type
         * @throws IllegalStateException
         *             if {@code bind} has already been called
         */
        public <NEWTARGET> Binding<BEAN, FIELDVALUE, NEWTARGET> withConverter(
                Converter<TARGET, NEWTARGET> converter);

        /**
         * Maps the binding to another data type using the mapping functions and
         * a possible exception as the error message.
         * <p>
         * The mapping functions are used to convert between a presentation
         * type, which must match the current target data type of the binding,
         * and a model type, which can be any data type and becomes the new
         * target type of the binding. When invoking
         * {@link #bind(Function, BiConsumer)}, the target type of the binding
         * must match the getter/setter types.
         * <p>
         * For instance, a {@code TextField} can be bound to an integer-typed
         * property using appropriate functions such as:
         * <code>withConverter(Integer::valueOf, String::valueOf);</code>
         *
         * @param <NEWTARGET>
         *            the type to convert to
         * @param toModel
         *            the function which can convert from the old target type to
         *            the new target type
         * @param toPresentation
         *            the function which can convert from the new target type to
         *            the old target type
         * @return a new binding with the appropriate type
         * @throws IllegalStateException
         *             if {@code bind} has already been called
         */
        public default <NEWTARGET> Binding<BEAN, FIELDVALUE, NEWTARGET> withConverter(
                Function<TARGET, NEWTARGET> toModel,
                Function<NEWTARGET, TARGET> toPresentation) {
            return withConverter(Converter.from(toModel, toPresentation,
                    exception -> exception.getMessage()));
        }

        /**
         * Maps the binding to another data type using the mapping functions and
         * the given error error message if a value cannot be converted to the
         * new target type.
         * <p>
         * The mapping functions are used to convert between a presentation
         * type, which must match the current target data type of the binding,
         * and a model type, which can be any data type and becomes the new
         * target type of the binding. When invoking
         * {@link #bind(Function, BiConsumer)}, the target type of the binding
         * must match the getter/setter types.
         * <p>
         * For instance, a {@code TextField} can be bound to an integer-typed
         * property using appropriate functions such as:
         * <code>withConverter(Integer::valueOf, String::valueOf);</code>
         *
         * @param <NEWTARGET>
         *            the type to convert to
         * @param toModel
         *            the function which can convert from the old target type to
         *            the new target type
         * @param toPresentation
         *            the function which can convert from the new target type to
         *            the old target type
         * @param errorMessage
         *            the error message to use if conversion using
         *            <code>toModel</code> fails
         * @return a new binding with the appropriate type
         * @throws IllegalStateException
         *             if {@code bind} has already been called
         */
        public default <NEWTARGET> Binding<BEAN, FIELDVALUE, NEWTARGET> withConverter(
                Function<TARGET, NEWTARGET> toModel,
                Function<NEWTARGET, TARGET> toPresentation,
                String errorMessage) {
            return withConverter(Converter.from(toModel, toPresentation,
                    exception -> errorMessage));
        }

        /**
         * Gets the field the binding uses.
         *
         * @return the field for the binding
         */
        public HasValue<FIELDVALUE> getField();

        /**
         * Sets the given {@code label} to show an error message if validation
         * fails.
         * <p>
         * The validation state of each field is updated whenever the user
         * modifies the value of that field. The validation state is by default
         * shown using {@link AbstractComponent#setComponentError} which is used
         * by the layout that the field is shown in. Most built-in layouts will
         * show this as a red exclamation mark icon next to the component, so
         * that hovering or tapping the icon shows a tooltip with the message
         * text.
         * <p>
         * This method allows to customize the way a binder displays error
         * messages to get more flexibility than what
         * {@link AbstractComponent#setComponentError} provides (it replaces the
         * default behavior).
         * <p>
         * This is just a shorthand for
         * {@link #withStatusChangeHandler(StatusChangeHandler)} method where
         * the handler instance hides the {@code label} if there is no error and
         * shows it with validation error message if validation fails. It means
         * that it cannot be called after
         * {@link #withStatusChangeHandler(StatusChangeHandler)} method call or
         * {@link #withStatusChangeHandler(StatusChangeHandler)} after this
         * method call.
         *
         * @see #withStatusChangeHandler(StatusChangeHandler)
         * @see AbstractComponent#setComponentError(ErrorMessage)
         * @param label
         *            label to show validation status for the field
         * @return this binding, for chaining
         */
        public default Binding<BEAN, FIELDVALUE, TARGET> withStatusLabel(
                Label label) {
            return withStatusChangeHandler(event -> {
                label.setValue(event.getMessage().orElse(""));
                // Only show the label when validation has failed
                label.setVisible(
                        ValidationStatus.ERROR.equals(event.getStatus()));
            });
        }

        /**
         * Sets a {@link StatusChangeHandler} to track validation status
         * changes.
         * <p>
         * The validation state of each field is updated whenever the user
         * modifies the value of that field. The validation state is by default
         * shown using {@link AbstractComponent#setComponentError} which is used
         * by the layout that the field is shown in. Most built-in layouts will
         * show this as a red exclamation mark icon next to the component, so
         * that hovering or tapping the icon shows a tooltip with the message
         * text.
         * <p>
         * This method allows to customize the way a binder displays error
         * messages to get more flexibility than what
         * {@link AbstractComponent#setComponentError} provides (it replaces the
         * default behavior).
         * <p>
         * The method may be called only once. It means there is no chain unlike
         * {@link #withValidator(Validator)} or
         * {@link #withConverter(Converter)}. Also it means that the shorthand
         * method {@link #withStatusLabel(Label)} also may not be called after
         * this method.
         *
         * @see #withStatusLabel(Label)
         * @see AbstractComponent#setComponentError(ErrorMessage)
         * @param handler
         *            status change handler
         * @return this binding, for chaining
         */
        public Binding<BEAN, FIELDVALUE, TARGET> withStatusChangeHandler(
                StatusChangeHandler handler);

        /**
         * Validates the field value and returns a {@code Result} instance
         * representing the outcome of the validation.
         *
         * @see Binder#validate()
         * @see Validator#apply(Object)
         *
         * @return the validation result.
         */
        public Result<TARGET> validate();

    }

    /**
     * An internal implementation of {@code Binding}.
     *
     * @param <BEAN>
     *            the bean type, must match the Binder bean type
     * @param <FIELDVALUE>
     *            the value type of the field
     * @param <TARGET>
     *            the target data type of the binding, matches the field type
     *            until a converter has been set
     */
    protected static class BindingImpl<BEAN, FIELDVALUE, TARGET>
            implements Binding<BEAN, FIELDVALUE, TARGET> {

        private final Binder<BEAN> binder;

        private final HasValue<FIELDVALUE> field;
        private Registration onValueChange;
        private StatusChangeHandler statusChangeHandler;
        private boolean isStatusHandlerChanged;

        private Function<BEAN, TARGET> getter;
        private BiConsumer<BEAN, TARGET> setter;

        /**
         * Contains all converters and validators chained together in the
         * correct order.
         */
        private Converter<FIELDVALUE, TARGET> converterValidatorChain;

        /**
         * Creates a new binding associated with the given field. Initializes
         * the binding with the given converter chain and status change handler.
         *
         * @param binder
         *            the binder this instance is connected to, not null
         * @param field
         *            the field to bind, not null
         * @param converterValidatorChain
         *            the converter/validator chain to use, not null
         * @param statusChangeHandler
         *            the handler to track validation status, not null
         */
        protected BindingImpl(Binder<BEAN> binder, HasValue<FIELDVALUE> field,
                Converter<FIELDVALUE, TARGET> converterValidatorChain,
                StatusChangeHandler statusChangeHandler) {
            this.field = field;
            this.binder = binder;
            this.converterValidatorChain = converterValidatorChain;
            this.statusChangeHandler = statusChangeHandler;
        }

        @Override
        public void bind(Function<BEAN, TARGET> getter,
                BiConsumer<BEAN, TARGET> setter) {
            checkUnbound();
            Objects.requireNonNull(getter, "getter cannot be null");

            this.getter = getter;
            this.setter = setter;
            getBinder().bindings.add(this);
            getBinder().getBean().ifPresent(this::bind);
        }

        @Override
        public Binding<BEAN, FIELDVALUE, TARGET> withValidator(
                Validator<? super TARGET> validator) {
            checkUnbound();
            Objects.requireNonNull(validator, "validator cannot be null");

            converterValidatorChain = converterValidatorChain
                    .chain(new ValidatorAsConverter<>(validator));
            return this;
        }

        @Override
        public <NEWTARGET> Binding<BEAN, FIELDVALUE, NEWTARGET> withConverter(
                Converter<TARGET, NEWTARGET> converter) {
            checkUnbound();
            Objects.requireNonNull(converter, "converter cannot be null");

            return getBinder().createBinding(getField(),
                    converterValidatorChain.chain(converter),
                    statusChangeHandler);
        }

        @Override
        public Binding<BEAN, FIELDVALUE, TARGET> withStatusChangeHandler(
                StatusChangeHandler handler) {
            checkUnbound();
            Objects.requireNonNull(handler, "handler cannot be null");
            if (isStatusHandlerChanged) {
                throw new IllegalStateException(
                        "A StatusChangeHandler has already been set");
            }
            isStatusHandlerChanged = true;
            statusChangeHandler = handler;
            return this;
        }

        @Override
        public HasValue<FIELDVALUE> getField() {
            return field;
        }

        /**
         * Returns the {@code Binder} connected to this {@code Binding}
         * instance.
         *
         * @return the binder
         */
        protected Binder<BEAN> getBinder() {
            return binder;
        }

        /**
         * Throws if this binding is already completed and cannot be modified
         * anymore.
         *
         * @throws IllegalStateException
         *             if this binding is already bound
         */
        protected void checkUnbound() {
            if (getter != null) {
                throw new IllegalStateException(
                        "cannot modify binding: already bound to a property");
            }
        }

        private void bind(BEAN bean) {
            setFieldValue(bean);
            onValueChange = getField()
                    .addValueChangeListener(e -> storeFieldValue(bean, true));
        }

        @Override
        public Result<TARGET> validate() {
            BinderResult<FIELDVALUE, TARGET> bindingResult = getTargetValue();
            getBinder().getStatusHandler().accept(Arrays.asList(bindingResult));
            return bindingResult;
        }

        /**
         * Returns the field value run through all converters and validators,
         * but doesn't fire a {@link ValidationStatusChangeEvent status change
         * event}.
         *
         * @return a result containing the validated and converted value or
         *         describing an error
         */
        private BinderResult<FIELDVALUE, TARGET> getTargetValue() {
            FIELDVALUE fieldValue = field.getValue();
            Result<TARGET> dataValue = converterValidatorChain.convertToModel(
                    fieldValue, ((AbstractComponent) field).getLocale());
            return dataValue.biMap((value, message) -> new BinderResult<>(this,
                    value, message));
        }

        private void unbind() {
            onValueChange.remove();
        }

        /**
         * Sets the field value by invoking the getter function on the given
         * bean.
         *
         * @param bean
         *            the bean to fetch the property value from
         */
        private void setFieldValue(BEAN bean) {
            assert bean != null;
            getField().setValue(convertDataToFieldType(bean));
        }

        private FIELDVALUE convertDataToFieldType(BEAN bean) {
            return converterValidatorChain.convertToPresentation(
                    getter.apply(bean),
                    ((AbstractComponent) getField()).getLocale());
        }

        /**
         * Saves the field value by invoking the setter function on the given
         * bean, if the value passes all registered validators. Optionally runs
         * item level validators if all field validators pass.
         *
         * @param bean
         *            the bean to set the property value to
         * @param runBeanLevelValidation
         *            <code>true</code> to run item level validators if all
         *            field validators pass, <code>false</code> to always skip
         *            item level validators
         */
        private void storeFieldValue(BEAN bean,
                boolean runBeanLevelValidation) {
            assert bean != null;
            if (setter != null) {
                BinderResult<FIELDVALUE, TARGET> validationResult = getTargetValue();
                getBinder().getStatusHandler()
                        .accept(Arrays.asList(validationResult));
                validationResult.ifOk(value -> setter.accept(bean, value));
            }
            if (runBeanLevelValidation && !getBinder().bindings.stream()
                    .map(BindingImpl::getTargetValue)
                    .anyMatch(Result::isError)) {
                binder.validateItem(bean);
            }
        }

        private void setBeanValue(BEAN bean, TARGET value) {
            setter.accept(bean, value);
        }

        private void fireStatusChangeEvent(Result<?> result) {
            ValidationStatusChangeEvent event = new ValidationStatusChangeEvent(
                    getField(),
                    result.isError() ? ValidationStatus.ERROR
                            : ValidationStatus.OK,
                    result.getMessage().orElse(null));
            statusChangeHandler.accept(event);
        }
    }

    /**
     * Wraps a validator as a converter.
     * <p>
     * The type of the validator must be of the same type as this converter or a
     * super type of it.
     *
     * @param <T>
     *            the type of the converter
     */
    private static class ValidatorAsConverter<T> implements Converter<T, T> {

        private Validator<? super T> validator;

        /**
         * Creates a new converter wrapping the given validator.
         *
         * @param validator
         *            the validator to wrap
         */
        public ValidatorAsConverter(Validator<? super T> validator) {
            this.validator = validator;
        }

        @Override
        public Result<T> convertToModel(T value, Locale locale) {
            Result<? super T> validationResult = validator.apply(value);
            if (validationResult.isError()) {
                return Result.error(validationResult.getMessage().get());
            } else {
                return Result.ok(value);
            }
        }

        @Override
        public T convertToPresentation(T value, Locale locale) {
            return value;
        }

    }

    private BEAN bean;

    private final Set<BindingImpl<BEAN, ?, ?>> bindings = new LinkedHashSet<>();

    private final List<Validator<? super BEAN>> validators = new ArrayList<>();

    private Label statusLabel;

    private BinderStatusHandler statusHandler;

    /**
     * Returns an {@code Optional} of the bean that has been bound with
     * {@link #bind}, or an empty optional if a bean is not currently bound.
     *
     * @return the currently bound bean if any
     */
    public Optional<BEAN> getBean() {
        return Optional.ofNullable(bean);
    }

    /**
     * Creates a new binding for the given field. The returned binding may be
     * further configured before invoking
     * {@link Binding#bind(Function, BiConsumer) Binding.bind} which completes
     * the binding. Until {@code Binding.bind} is called, the binding has no
     * effect.
     *
     * @param <FIELDVALUE>
     *            the value type of the field
     * @param field
     *            the field to be bound, not null
     * @return the new binding
     */
    public <FIELDVALUE> Binding<BEAN, FIELDVALUE, FIELDVALUE> forField(
            HasValue<FIELDVALUE> field) {
        Objects.requireNonNull(field, "field cannot be null");
        return createBinding(field, Converter.identity(),
                this::handleValidationStatusChange);
    }

    /**
     * Binds a field to a bean property represented by the given getter and
     * setter pair. The functions are used to update the field value from the
     * property and to store the field value to the property, respectively.
     * <p>
     * Use the {@link #forField(HasValue)} overload instead if you want to
     * further configure the new binding.
     * <p>
     * When a bean is bound with {@link Binder#bind(BEAN)}, the field value is
     * set to the return value of the given getter. The property value is then
     * updated via the given setter whenever the field value changes. The setter
     * may be null; in that case the property value is never updated and the
     * binding is said to be <i>read-only</i>.
     * <p>
     * If the Binder is already bound to some item, the newly bound field is
     * associated with the corresponding bean property as described above.
     * <p>
     * The getter and setter can be arbitrary functions, for instance
     * implementing user-defined conversion or validation. However, in the most
     * basic use case you can simply pass a pair of method references to this
     * method as follows:
     *
     * <pre>
     * class Person {
     *     public String getName() { ... }
     *     public void setName(String name) { ... }
     * }
     *
     * TextField nameField = new TextField();
     * binder.bind(nameField, Person::getName, Person::setName);
     * </pre>
     *
     * @param <FIELDVALUE>
     *            the value type of the field
     * @param field
     *            the field to bind, not null
     * @param getter
     *            the function to get the value of the property to the field,
     *            not null
     * @param setter
     *            the function to save the field value to the property or null
     *            if read-only
     */
    public <FIELDVALUE> void bind(HasValue<FIELDVALUE> field,
            Function<BEAN, FIELDVALUE> getter,
            BiConsumer<BEAN, FIELDVALUE> setter) {
        forField(field).bind(getter, setter);
    }

    /**
     * Binds the given bean to all the fields added to this Binder. To remove
     * the binding, call {@link #unbind()}.
     * <p>
     * When a bean is bound, the field values are updated by invoking their
     * corresponding getter functions. Any changes to field values are reflected
     * back to their corresponding property values of the bean as long as the
     * bean is bound.
     * <p>
     * Any change made in the fields also runs validation for the field
     * {@link Binding} and bean level validation for this binder (bean level
     * validators are added using {@link Binder#withValidator(Validator)}.
     *
     * @see #load(Object)
     * @see #save(Object)
     * @see #saveIfValid(Object)
     *
     * @param bean
     *            the bean to edit, not null
     */
    public void bind(BEAN bean) {
        Objects.requireNonNull(bean, "bean cannot be null");
        unbind();
        this.bean = bean;
        bindings.forEach(b -> b.bind(bean));
    }

    /**
     * Unbinds the currently bound bean if any. If there is no bound bean, does
     * nothing.
     */
    public void unbind() {
        if (bean != null) {
            bean = null;
            bindings.forEach(BindingImpl::unbind);
        }
    }

    /**
     * Reads the bound property values from the given bean to the corresponding
     * fields.
     * <p>
     * The bean is not otherwise associated with this binder; in particular its
     * property values are not bound to the field value changes. To achieve
     * that, use {@link #bind(BEAN)}.
     *
     * @see #bind(Object)
     * @see #saveIfValid(Object)
     * @see #save(Object)
     *
     * @param bean
     *            the bean whose property values to read, not null
     */
    public void load(BEAN bean) {
        Objects.requireNonNull(bean, "bean cannot be null");
        bindings.forEach(binding -> binding.setFieldValue(bean));
    }

    /**
     * Saves changes from the bound fields to the given bean if all validators
     * (binding and bean level) pass.
     * <p>
     * If any field binding validator fails, no values are saved and a
     * {@code ValidationException} is thrown.
     * <p>
     * If all field level validators pass, the given bean is updated and bean
     * level validators are run on the updated item. If any bean level validator
     * fails, the bean updates are reverted and a {@code ValidationException} is
     * thrown.
     *
     * @see #saveIfValid(Object)
     * @see #load(Object)
     * @see #bind(Object)
     *
     * @param bean
     *            the object to which to save the field values, not null
     * @throws ValidationException
     *             if some of the bound field values fail to validate
     */
    public void save(BEAN bean) throws ValidationException {
        List<ValidationError<?>> errors = doSaveIfValid(bean);
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    /**
     * Saves changes from the bound fields to the given bean if all validators
     * (binding and bean level) pass.
     * <p>
     * If any field binding validator fails, no values are saved and
     * <code>false</code> is returned.
     * <p>
     * If all field level validators pass, the given bean is updated and bean
     * level validators are run on the updated item. If any bean level validator
     * fails, the bean updates are reverted and <code>false</code> is returned.
     *
     * @see #save(Object)
     * @see #load(Object)
     * @see #bind(Object)
     *
     * @param bean
     *            the object to which to save the field values, not null
     * @return {@code true} if there was no validation errors and the bean was
     *         updated, {@code false} otherwise
     */
    public boolean saveIfValid(BEAN bean) {
        return doSaveIfValid(bean).isEmpty();
    }

    /**
     * Saves the field values into the given bean if all field level validators
     * pass. Runs bean level validators on the bean after saving.
     *
     * @param bean
     *            the bean to save field values into
     * @return a list of field validation errors if such occur, otherwise a list
     *         of bean validation errors.
     */
    private List<ValidationError<?>> doSaveIfValid(BEAN bean) {
        Objects.requireNonNull(bean, "bean cannot be null");
        // First run fields level validation
        List<ValidationError<?>> errors = validateBindings();
        // If no validation errors then update bean
        if (!errors.isEmpty()) {
            return errors;
        }

        // Save old bean values so we can restore them if validators fail
        Map<Binding<BEAN, ?, ?>, Object> oldValues = new HashMap<>();
        bindings.forEach(binding -> oldValues.put(binding,
                binding.convertDataToFieldType(bean)));

        bindings.forEach(binding -> binding.storeFieldValue(bean, false));
        // Now run bean level validation against the updated bean
        List<ValidationError<?>> itemValidatorErrors = validateItem(bean);
        if (!itemValidatorErrors.isEmpty()) {
            // Item validator failed, revert values
            bindings.forEach((BindingImpl binding) -> binding.setBeanValue(bean,
                    oldValues.get(binding)));
        }
        return itemValidatorErrors;
    }

    /**
     * Adds an item level validator.
     * <p>
     * Item level validators are applied on the item instance after the item is
     * updated. If the validators fail, the item instance is reverted to its
     * previous state.
     *
     * @see #save(Object)
     * @see #saveIfValid(Object)
     *
     * @param validator
     *            the validator to add, not null
     * @return this binder, for chaining
     */
    public Binder<BEAN> withValidator(Validator<? super BEAN> validator) {
        Objects.requireNonNull(validator, "validator cannot be null");
        validators.add(validator);
        return this;
    }

    /**
     * Validates the values of all bound fields and returns the result of the
     * validation as a list of validation errors.
     * <p>
     * If all field level validators pass, and {@link #bind(Object)} has been
     * used to bind to an item, item level validators are run for that bean.
     * Item level validators are ignored if there is no bound item or if any
     * field level validator fails.
     * <p>
     * Validation is successful if the returned list is empty.
     *
     * @return a list of validation errors or an empty list if validation
     *         succeeded
     */
    public List<ValidationError<?>> validate() {
        List<ValidationError<?>> errors = validateBindings();
        if (!errors.isEmpty()) {
            return errors;
        }

        if (bean != null) {
            return validateItem(bean);
        }

        return Collections.emptyList();
    }

    /**
     * Validates the bindings and returns the result of the validation as a list
     * of validation errors.
     * <p>
     * If all validators pass, the resulting list is empty.
     * <p>
     * Does not run bean validators.
     * <p>
     * All results are passed to the {@link #getStatusHandler() status change
     * handler.}
     *
     * @see #validateItem(Object)
     *
     * @return a list of validation errors or an empty list if validation
     *         succeeded
     */
    private List<ValidationError<?>> validateBindings() {
        List<BinderResult<?, ?>> results = new ArrayList<>();
        for (BindingImpl<?, ?, ?> binding : bindings) {
            results.add(binding.getTargetValue());
        }

        getStatusHandler().accept(Collections.unmodifiableList(results));

        return results.stream().filter(r -> r.isError())
                .map(r -> new ValidationError<>(r.getBinding().get(),
                        r.getField().get().getValue(), r.getMessage().get()))
                .collect(Collectors.toList());
    }

    /**
     * Validates the {@code item} using item validators added using
     * {@link #withValidator(Validator)} and returns the result of the
     * validation as a list of validation errors.
     * <p>
     * If all validators pass, the resulting list is empty.
     *
     * @see #withValidator(Validator)
     *
     * @param bean
     *            the bean to validate
     * @return a list of validation errors or an empty list if validation
     *         succeeded
     */
    private List<ValidationError<?>> validateItem(BEAN bean) {
        Objects.requireNonNull(bean, "bean cannot be null");
        List<BinderResult<?, ?>> results = Collections.unmodifiableList(
                validators.stream().map(validator -> validator.apply(bean))
                        .map(dataValue -> dataValue.biMap(
                                (value, message) -> new BinderResult<>(null,
                                        value, message)))
                        .collect(Collectors.toList()));
        getStatusHandler().accept(results);

        return results.stream()
                .filter(Result::isError).map(res -> new ValidationError<>(this,
                        bean, res.getMessage().get()))
                .collect(Collectors.toList());
    }

    /**
     * Sets the label to show the binder level validation errors not related to
     * any specific field.
     * <p>
     * Only the one validation error message is shown in this label at a time.
     * <p>
     * This is a convenience method for
     * {@link #setStatusHandler(BinderStatusHandler)}, which means that this
     * method cannot be used after the handler has been set. Also the handler
     * cannot be set after this label has been set.
     *
     * @param statusLabel
     *            the status label to set
     * @see #setStatusHandler(BinderStatusHandler)
     * @see Binding#withStatusLabel(Label)
     */
    public void setStatusLabel(Label statusLabel) {
        if (statusHandler != null) {
            throw new IllegalStateException("Cannot set status label if a "
                    + BinderStatusHandler.class.getSimpleName()
                    + " has already been set.");
        }
        this.statusLabel = statusLabel;
    }

    /**
     * Gets the status label or an empty optional if none has been set.
     *
     * @return the optional status label
     * @see #setStatusLabel(Label)
     */
    public Optional<Label> getStatusLabel() {
        return Optional.ofNullable(statusLabel);
    }

    /**
     * Sets the status handler to track form status changes.
     * <p>
     * Setting this handler will override the default behavior, which is to let
     * fields show their validation status messages and show binder level
     * validation errors or OK status in the label set with
     * {@link #setStatusLabel(Label)}.
     * <p>
     * This handler cannot be set after the status label has been set with
     * {@link #setStatusLabel(Label)}, or {@link #setStatusLabel(Label)} cannot
     * be used after this handler has been set.
     *
     * @param statusHandler
     *            the status handler to set, not <code>null</code>
     * @throws NullPointerException
     *             for <code>null</code> status handler
     * @see #setStatusLabel(Label)
     * @see Binding#withStatusChangeHandler(StatusChangeHandler)
     */
    public void setStatusHandler(BinderStatusHandler statusHandler) {
        Objects.requireNonNull(statusHandler, "Cannot set a null "
                + BinderStatusHandler.class.getSimpleName());
        if (statusLabel != null) {
            throw new IllegalStateException(
                    "Cannot set " + BinderStatusHandler.class.getSimpleName()
                            + " if a status label has already been set.");
        }
        this.statusHandler = statusHandler;
    }

    /**
     * Gets the status handler of this form.
     * <p>
     * If none has been set with {@link #setStatusHandler(BinderStatusHandler)},
     * the default implementation is returned.
     *
     * @return the status handler used, never <code>null</code>
     * @see #setStatusHandler(BinderStatusHandler)
     */
    public BinderStatusHandler getStatusHandler() {
        return Optional.ofNullable(statusHandler)
                .orElse(this::defaultHandleBinderStatusChange);
    }

    /**
     * Creates a new binding with the given field.
     *
     * @param <FIELDVALUE>
     *            the value type of the field
     * @param <TARGET>
     *            the target data type
     * @param field
     *            the field to bind, not null
     * @param converter
     *            the converter for converting between FIELDVALUE and TARGET
     *            types, not null
     * @param handler
     *            the handler to notify of status changes, not null
     * @return the new incomplete binding
     */
    protected <FIELDVALUE, TARGET> BindingImpl<BEAN, FIELDVALUE, TARGET> createBinding(
            HasValue<FIELDVALUE> field, Converter<FIELDVALUE, TARGET> converter,
            StatusChangeHandler handler) {
        return new BindingImpl<>(this, field, converter, handler);
    }

    /**
     * Clears the error condition of the given field, if any. The default
     * implementation clears the
     * {@link AbstractComponent#setComponentError(ErrorMessage) component error}
     * of the field if it is a Component, otherwise does nothing.
     *
     * @param field
     *            the field with an invalid value
     */
    protected void clearError(HasValue<?> field) {
        if (field instanceof AbstractComponent) {
            ((AbstractComponent) field).setComponentError(null);
        }
    }

    /**
     * Handles a validation error emitted when trying to save the value of the
     * given field. The default implementation sets the
     * {@link AbstractComponent#setComponentError(ErrorMessage) component error}
     * of the field if it is a Component, otherwise does nothing.
     *
     * @param field
     *            the field with the invalid value
     * @param error
     *            the error message to set
     */
    protected void handleError(HasValue<?> field, String error) {
        if (field instanceof AbstractComponent) {
            ((AbstractComponent) field).setComponentError(new UserError(error));
        }

    }

    /**
     * Default {@link StatusChangeHandler} functional method implementation.
     *
     * @param event
     *            the validation event
     */
    protected void handleValidationStatusChange(
            ValidationStatusChangeEvent event) {
        HasValue<?> source = event.getSource();
        clearError(source);
        if (Objects.equals(ValidationStatus.ERROR, event.getStatus())) {
            handleError(source, event.getMessage().get());
        }
    }

    /**
     * The default binder level status handler.
     * <p>
     * Passes all field related results to the Binding status handlers. All
     * other status changes are displayed in the status label, if one has been
     * set with {@link #setStatusLabel(Label)}.
     *
     * @param results
     *            a list of validation results from binding and/or item level
     *            validators
     */
    @SuppressWarnings("unchecked")
    protected void defaultHandleBinderStatusChange(
            List<BinderResult<?, ?>> results) {
        // let field events go to binding status handlers
        results.stream().filter(br -> br.getField().isPresent())
                .forEach(br -> ((BindingImpl<BEAN, ?, ?>) br.getBinding().get())
                        .fireStatusChangeEvent(br));

        // show first possible error or OK status in the label if set
        if (getStatusLabel().isPresent()) {
            String statusMessage = results.stream()
                    .filter(r -> !r.getField().isPresent())
                    .map(Result::getMessage).map(m -> m.orElse("")).findFirst()
                    .orElse("");
            getStatusLabel().get().setValue(statusMessage);
        }
    }

}