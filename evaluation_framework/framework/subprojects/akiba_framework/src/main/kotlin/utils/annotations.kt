package org.iotsplab.akiba.utils

import com.google.gson.JsonDeserializer
import com.google.gson.JsonSerializer
import kotlin.reflect.KClass

/**
 * WithConfigClass: Used in any subclass of `AkibaModule` to specify the config class for this module
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class WithConfigClass(val clazz: KClass<*>)

/**
 * WithConfigSerializer: Used in any subclass of `AkibaModule` to specify the serializer for this module's config class
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class WithConfigSerializer(val serializer: KClass<out JsonSerializer<*>>)

/**
 * WithConfigDeSerializer: Used in any subclass of `AkibaModule` to specify the deserializer for this module's config class
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class WithConfigDeserializer(val deserializer: KClass<out JsonDeserializer<*>>)

/**
 * TaskInterface: Annotation for exposing a function.
 * Latter tasks can call methods with this annotation in former tasks
 * Before a task's starting to run, all its methods with this annotation will be registered in the task coroutine
 * context.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class TaskInterface

/**
 * IgnoreRuntimeTimeout: To make runtime timeout disabled, make sure your task is closable before adding this annotation
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class IgnoreRuntimeTimeout

/**
 * RouteRequestMethod: A required annotation for all subclasses of `DynamicServer` to specify the request method
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RouteRequestMethod(val method: Array<String>)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class NoNeedToClose

/**
 * RequireProperty: To point out a property that is required for the task to run.
 * Note: Do not mistake `RequireProperty` with `RequireDependency`, properties for tasks is preset in database while
 *       dependencies for tasks are all tasks that need to be done before this task
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable
annotation class RequireProperty<T>(val propertyName: String, val optional: Boolean = false)

/**
 * RequireDependency: To point out modules that are required for a module to run.
 * Note: It doesn't mean that if module A has a dependency on module B, module B should be done before module A. It only
 *       means that module A need something defined in module B.
 */
@Deprecated("Akiba will generate dependency attributes in MANIFEST.MF soon," +
            " no need to specify this on `AkibaModule`")
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequireDependency(val dependencies: Array<String>)

/**
 * DataProducer: To point out that this task will set some data to the coroutine context for THIS program
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable
annotation class DataProducer<T>(val propertyName: String)

/**
 * DataConsumer: To point out that this task will get some data from the coroutine context for THIS program
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable
annotation class DataConsumer<T>(val propertyName: String)

/**
 * PureDependency: Any subclass of `AutoProcess` with this annotation will skip its process, which means that
 * this class can only import something dynamically for other tasks to use
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class PureDependency

/**
 * DatabaseColumn: Used in any subclass of `AutoProcess` to specify the columns that needed to be defined for this
 *                 module to save data
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable
annotation class WithTableColumn(val name: String, val type: String)

/**
 * DatabaseView: Used in any subclass of `AutoProcess` to specify the view that needed to be defined for this
 *               module. In some occasions, a view is needed to inspect data in a certain way, especially when
 *               there are actually multiple lines of data under one ID, the data can only be saved in JSON
 *               format in tables, you can write a view to extract the JSON strings to build a view which is more
 *               readable.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable
annotation class WithView(val viewName: String, val creationSql: String)

/**
 * NoCreateDatabase: Used in any subclass of `AutoProcess` to specify that this module does not need to create database
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class DoNotCreateTable

/**
 * FailOnTimeout: Used in any subclass of `AutoProcess` to specify that if this task goes timeout, the task will fail
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class FailOnCancelled