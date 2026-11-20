/*
 * SAMAVETA — offline JVM verification shim.
 *
 * Purpose: the sandbox that develops this repository has no access to Maven Central and no
 * Android SDK, so Gradle/JUnit cannot be resolved there. This file provides a *source-compatible*
 * subset of the JUnit 4 / kotlin.test API surface, plus a tiny runner, so the pure-Kotlin domain
 * tests in `core/src/test` can be compiled and executed with the standalone Kotlin compiler.
 *
 * This shim is ONLY used by `tools/jvm-verify/verify.sh`. The real Gradle build uses JUnit 4 from
 * Maven Central. Tests written against this shim are equally valid under the real JUnit 4 runtime
 * because only the annotations/assertions below (all standard JUnit symbols) are referenced.
 */
@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package org.junit

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Test(
    val expected: kotlin.reflect.KClass<out Throwable> = None::class,
    val timeout: Long = 0L,
)

/** Sentinel used as the default value of [Test.expected]. */
class None : Throwable()

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Before

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class After

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class Ignore(val value: String = "")

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class DisplayName(val value: String)

/**
 * Minimal subset of org.junit.Assert, with JUnit 4's *message-first* argument order so tests written
 * against this shim compile unchanged against the real JUnit 4 from Maven Central.
 */
object Assert {
    @JvmStatic
    fun fail(message: String? = null): Nothing = throw AssertionError(message ?: "test failed")

    @JvmStatic
    fun assertTrue(condition: Boolean) = assertTrue(null, condition)

    @JvmStatic
    fun assertTrue(message: String?, condition: Boolean) {
        if (!condition) fail(message ?: "expected condition to be true")
    }

    @JvmStatic
    fun assertFalse(condition: Boolean) = assertFalse(null, condition)

    @JvmStatic
    fun assertFalse(message: String?, condition: Boolean) {
        if (condition) fail(message ?: "expected condition to be false")
    }

    @JvmStatic
    fun assertEquals(expected: Any?, actual: Any?) = assertEquals(null, expected, actual)

    @JvmStatic
    fun assertEquals(message: String?, expected: Any?, actual: Any?) {
        if (!valuesEqual(expected, actual)) {
            fail("${message ?: "values differ"}\n  expected: <$expected>\n  actual:   <$actual>")
        }
    }

    @JvmStatic
    fun assertEquals(expected: Double, actual: Double, delta: Double) =
        assertEquals(null, expected, actual, delta)

    @JvmStatic
    fun assertEquals(message: String?, expected: Double, actual: Double, delta: Double) {
        if (java.lang.Double.isNaN(expected) || java.lang.Double.isNaN(actual)) {
            if (!(java.lang.Double.isNaN(expected) && java.lang.Double.isNaN(actual))) {
                fail("${message ?: "values differ"} expected=<$expected> actual=<$actual>")
            }
            return
        }
        if (Math.abs(expected - actual) > delta) {
            fail("${message ?: "values differ"} expected=<$expected> actual=<$actual> delta=<$delta>")
        }
    }

    @JvmStatic
    fun assertNotEquals(unexpected: Any?, actual: Any?) = assertNotEquals(null, unexpected, actual)

    @JvmStatic
    fun assertNotEquals(message: String?, unexpected: Any?, actual: Any?) {
        if (valuesEqual(unexpected, actual)) {
            fail("${message ?: "values should differ"} both were <$actual>")
        }
    }

    @JvmStatic
    fun assertNull(value: Any?) = assertNull(null, value)

    @JvmStatic
    fun assertNull(message: String?, value: Any?) {
        if (value != null) fail("${message ?: "expected null"} was <$value>")
    }

    @JvmStatic
    fun assertNotNull(value: Any?) = assertNotNull(null, value)

    @JvmStatic
    fun assertNotNull(message: String?, value: Any?) {
        if (value == null) fail(message ?: "expected non-null value")
    }

    @JvmStatic
    fun assertSame(expected: Any?, actual: Any?) = assertSame(null, expected, actual)

    @JvmStatic
    fun assertSame(message: String?, expected: Any?, actual: Any?) {
        if (expected !== actual) fail(message ?: "expected same instance")
    }

    @JvmStatic
    fun assertNotSame(unexpected: Any?, actual: Any?) = assertNotSame(null, unexpected, actual)

    @JvmStatic
    fun assertNotSame(message: String?, unexpected: Any?, actual: Any?) {
        if (unexpected === actual) fail(message ?: "expected different instances")
    }

    @JvmStatic
    fun assertArrayEquals(expected: ByteArray, actual: ByteArray) = assertArrayEquals(null, expected, actual)

    @JvmStatic
    fun assertArrayEquals(message: String?, expected: ByteArray, actual: ByteArray) {
        if (!expected.contentEquals(actual)) fail(message ?: "byte arrays differ")
    }

    @JvmStatic
    fun assertArrayEquals(expected: IntArray, actual: IntArray) = assertArrayEquals(null, expected, actual)

    @JvmStatic
    fun assertArrayEquals(message: String?, expected: IntArray, actual: IntArray) {
        if (!expected.contentEquals(actual)) fail(message ?: "int arrays differ")
    }

    private fun valuesEqual(a: Any?, b: Any?): Boolean {
        if (a == null || b == null) return a == null && b == null
        // Numeric literals arrive boxed as Int/Long/Double; compare numerically before equality.
        if (a is Number && b is Number) {
            val integral = a is Int || a is Long || a is Short || a is Byte
            val otherIntegral = b is Int || b is Long || b is Short || b is Byte
            if (integral && otherIntegral) return a.toLong() == b.toLong()
            return a.toDouble() == b.toDouble()
        }
        return a == b
    }
}

/** Very small runner: discovers all classes under the given package roots and runs their tests. */
object ShimRunner {
    class Failure(val testClass: String, val method: String, val error: Throwable)

    fun run(roots: List<String>, classLoader: ClassLoader = ShimRunner::class.java.classLoader): List<Failure> {
        val classNames = discover(roots)
        val failures = mutableListOf<Failure>()
        var passed = 0
        var skipped = 0
        for (name in classNames.sorted()) {
            val clazz = try {
                Class.forName(name, true, classLoader)
            } catch (t: Throwable) {
                failures += Failure(name, "<load>", t)
                continue
            }
            if (clazz.isAnnotationPresent(Ignore::class.java)) {
                skipped++
                continue
            }
            if (Modifier.isAbstract(clazz.modifiers) || clazz.isInterface) continue
            val methods = clazz.declaredMethods
            val befores = methods.filter { it.isAnnotationPresent(Before::class.java) }
            val afters = methods.filter { it.isAnnotationPresent(After::class.java) }
            val tests = methods.filter { it.isAnnotationPresent(Test::class.java) }
            if (tests.isEmpty()) continue
            for (m in tests.sortedBy { it.name }) {
                if (m.isAnnotationPresent(Ignore::class.java)) {
                    skipped++
                    continue
                }
                val instance = try {
                    clazz.getDeclaredConstructor().newInstance()
                } catch (t: Throwable) {
                    failures += Failure(clazz.name, m.name, t)
                    continue
                }
                val expected = m.getAnnotation(Test::class.java).expected.takeIf { it != None::class }
                try {
                    for (b in befores) invoke(b, instance)
                    invoke(m, instance)
                    if (expected != null) {
                        failures += Failure(clazz.name, m.name, AssertionError("expected ${expected.simpleName} to be thrown"))
                    } else {
                        passed++
                    }
                } catch (t: Throwable) {
                    val cause = (t as? InvocationTargetException)?.targetException ?: t
                    if (expected != null && expected.java.isInstance(cause)) {
                        passed++
                    } else {
                        failures += Failure(clazz.name, m.name, cause)
                    }
                } finally {
                    for (a in afters) {
                        try {
                            invoke(a, instance)
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        }
        println("------------------------------------------------------------------------")
        println("Tests run: ${passed + failures.size}, Passed: $passed, Failed: ${failures.size}, Skipped: $skipped")
        failures.forEach { f ->
            println()
            println("FAILED  ${f.testClass} > ${f.method}")
            println("        ${f.error::class.java.name}: ${f.error.message?.replace("\n", "\n        ")}")
            f.error.stackTrace.take(6).drop(1).forEach { println("          at $it") }
        }
        return failures
    }

    private fun invoke(m: java.lang.reflect.Method, instance: Any) {
        m.isAccessible = true
        m.invoke(instance)
    }

    private fun discover(roots: List<String>): Set<String> {
        val out = linkedSetOf<String>()
        val loader = ShimRunner::class.java.classLoader
        for (root in roots) {
            val pkgPath = root.replace('.', '/')
            // A package can be split across several classpath entries (main/, test/), so enumerate
            // every matching resource rather than only the first.
            val urls = loader.getResources(pkgPath).toList()
            for (url in urls) {
                if (url.protocol != "file") continue
                val dir = java.io.File(url.toURI())
                if (!dir.isDirectory) continue
                dir.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".class") && !it.name.contains('$') }
                    .forEach { f ->
                        val rel = f.relativeTo(dir).path.removeSuffix(".class")
                            .replace(java.io.File.separatorChar, '.')
                        out += "$root.$rel"
                    }
            }
        }
        return out
    }
}
