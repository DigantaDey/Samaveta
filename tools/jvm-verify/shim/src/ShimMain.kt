@file:JvmName("SamavetaVerify")

/*
 * Entry point for the offline JVM verification run.
 * Usage: java -cp <classes> SamavetaVerifyKt <test.package.root>...
 */
fun main(args: Array<String>) {
    val roots = args.toList().ifEmpty { listOf("com.samaveta.android.core") }
    println("SAMAVETA offline JVM verification")
    println("root packages: $roots")
    val failures = org.junit.ShimRunner.run(roots)
    if (failures.isNotEmpty()) {
        println()
        println("VERDICT: FAIL (${failures.size} failing test(s))")
        kotlin.system.exitProcess(1)
    }
    println("VERDICT: PASS")
}
