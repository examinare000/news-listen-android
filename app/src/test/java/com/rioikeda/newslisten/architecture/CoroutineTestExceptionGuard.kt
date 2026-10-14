package com.rioikeda.newslisten.architecture

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * kotlinx-coroutines-test の `ExceptionCollector`（service として登録された platform の handler）は、`runTest` が一度でも動いた JVM では
 * 有効のまま残り、callback が無い間の未捕捉の例外を溜めて、次の `runTest` の開始時に失敗させる。
 *
 * runner は所有者の内部 scope の未捕捉の例外を thread の handler で受けて失敗にする（その例外は、実行単位の失敗として報告済み）。
 * 同じ例外が collector にも溜まると、無関係な次の `runTest` が壊れるので、実行単位の間に溜まった分だけを取り除く。
 * 実行単位の前から溜まっていた他のテストの例外は、取り除かずに元へ戻す。
 *
 * collector が有効でなければ（`runTest` がまだ動いていなければ）何もしない。collector を有効にする副作用を持たない。
 */
internal object CoroutineTestExceptionGuard {

    private class Collector(
        val instance: Any,
        val enabled: Field,
        val addCallback: Method,
        val removeCallback: Method,
        val report: Method,
    )

    private val collector: Collector? by lazy {
        val type = try {
            Class.forName("kotlinx.coroutines.test.internal.ExceptionCollector")
        } catch (e: ClassNotFoundException) {
            return@lazy null
        }
        try {
            Collector(
                instance = type.getField("INSTANCE").get(null),
                enabled = type.getDeclaredField("enabled").apply { isAccessible = true },
                addCallback = type.getMethod("addOnExceptionCallback", Any::class.java, Function1::class.java),
                removeCallback = type.getMethod("removeOnExceptionCallback", Any::class.java),
                report = type.getMethod("handleException", Throwable::class.java),
            )
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException(
                "kotlinx-coroutines-test の ExceptionCollector の形が想定と違う。runner が溜まった例外を取り除けない（版を確かめる）",
                e,
            )
        }
    }

    /** [block] の間に溜まった未捕捉の例外を collector から取り除く。 */
    fun <T> protect(block: () -> T): T {
        val kept = takeStored()
        try {
            return block()
        } finally {
            takeStored()
            kept.forEach { restore(it) }
        }
    }

    private fun isEnabled(collector: Collector): Boolean = collector.enabled.getBoolean(null)

    /** 溜まっている例外を取り出す（取り出した分は collector から消える）。 */
    private fun takeStored(): List<Throwable> {
        val target = collector ?: return emptyList()
        if (!isEnabled(target)) return emptyList()
        val taken = mutableListOf<Throwable>()
        val owner = Any()
        val callback: (Throwable) -> Unit = { taken += it }
        target.addCallback.invoke(target.instance, owner, callback)
        target.removeCallback.invoke(target.instance, owner)
        return taken
    }

    private fun restore(throwable: Throwable) {
        val target = collector ?: return
        target.report.invoke(target.instance, throwable)
    }
}
