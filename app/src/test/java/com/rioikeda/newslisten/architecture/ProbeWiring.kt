package com.rioikeda.newslisten.architecture

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Type
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher

/**
 * probe のグラフ（所有者から、宣言の型が効果を持ちうる母集団の class か `PORT_BACKED` の field を辿って得るオブジェクトの集合）の
 * 走査と、その各 field への配線の検査（配線の規則の表）、公開 callback への代入。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ProbeWiring(
    private val classifier: TypeClassifier,
    private val effectful: Set<Class<*>>,
    private val hookRows: Map<String, HookRow>,
) {

    /** オブジェクトの field を持つ class の列。母集団の class・自分の class・`PORT_BACKED` の class だけ。 */
    private fun chainOf(obj: Any): List<Class<*>> =
        generateSequence(obj.javaClass) { it.superclass }
            .takeWhile { it != Any::class.java }
            .filter { it === obj.javaClass || it in classifier.populationClasses || classifier.classify(it) == TypeClass.PORT_BACKED }
            .toList()

    /** 宣言の順ではなく名前の順に並べた、非 static の field（`isAccessible` にして返す）。 */
    fun instanceFields(obj: Any): List<Field> =
        chainOf(obj).flatMap { type ->
            type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.sortedBy { it.name }
                .onEach { it.isAccessible = true }
        }

    fun label(field: Field): String = "${OperationEnumerator.typeName(field.declaringClass)}#${field.name}"

    private fun shouldTraverse(type: Class<*>): Boolean {
        val classification = classifier.classify(type)
        return classification == TypeClass.PORT_BACKED || (classification == TypeClass.POPULATION && type in effectful)
    }

    /** 同一性で 1 回ずつ数えたグラフ（発見の順）。 */
    fun graphOf(owner: Any): List<Any> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val result = mutableListOf<Any>()
        fun visit(obj: Any) {
            if (!seen.add(obj)) return
            result += obj
            for (field in instanceFields(obj)) {
                if (!shouldTraverse(field.type)) continue
                field.get(obj)?.let { visit(it) }
            }
        }
        visit(owner)
        return result
    }

    /** 全 `FUNCTION` の field の値。 */
    fun functionFields(graph: List<Any>): List<FunctionFieldValue> =
        graph.flatMap { obj ->
            instanceFields(obj).filter { isFunction(it.type) }.map { FunctionFieldValue(obj, label(it), it.get(obj)) }
        }

    /** hook 表で配線 `SETTER` の field へ、public な setter でその実行単位の工場の WRITE hook を代入する。代入できない行は失敗。 */
    fun assignSetters(graph: List<Any>, hooks: Hooks): List<Finding> {
        val findings = mutableListOf<Finding>()
        for (obj in graph) {
            for (field in instanceFields(obj).filter { isFunction(it.type) }) {
                val label = label(field)
                val row = hookRows[label]?.takeIf { it.wiring == HookWiring.SETTER } ?: continue
                if (row.kind != HookKind.WRITE) {
                    findings += Finding(Headings.UNASSIGNABLE_SETTER, label, "SETTER の行は WRITE に限る（表: ${row.kind}）")
                    continue
                }
                val setter = setterOf(obj, field)
                if (setter == null) {
                    findings += Finding(Headings.UNASSIGNABLE_SETTER, label, "field ${field.name} に public な setter が無い")
                    continue
                }
                val value: Any = when (row.shape) {
                    HookShape.NO_ARG -> hooks.write(label)
                    HookShape.SUSPEND_NO_ARG -> hooks.suspendWrite(label)
                    HookShape.ONE_ARG -> hooks.write1<Any?>(label)
                    HookShape.SUSPEND_ONE_ARG -> hooks.suspendWrite1<Any?>(label)
                }
                try {
                    setter.invoke(obj, value)
                } catch (e: Exception) {
                    findings += Finding(Headings.UNASSIGNABLE_SETTER, label, "setter ${setter.name} の呼出に失敗: ${e.cause ?: e}")
                }
            }
        }
        return findings
    }

    private fun setterOf(obj: Any, field: Field): Method? {
        val name = "set" + field.name.replaceFirstChar { it.uppercase() }
        return obj.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == 1 && !Modifier.isStatic(it.modifiers) && isFunction(it.parameterTypes[0])
        }
    }

    /** グラフの全 field に、宣言の型で配線の規則の表を当てる。 */
    fun check(graph: List<Any>, env: ProbeEnvironment): List<Finding> =
        graph.flatMap { obj ->
            instanceFields(obj).mapNotNull { field ->
                checkValue(label(field), field.genericType, field.get(obj), env, hookRows[label(field)])
            }
        }

    /** 呼び方の引数 1 つを、宣言の型で同じ規則の表に当てる（hook 表の行は無い）。 */
    fun checkArgument(label: String, declared: Type, value: Any?, env: ProbeEnvironment): Finding? =
        checkValue(label, declared, value, env, null)

    private fun checkValue(label: String, declared: Type, value: Any?, env: ProbeEnvironment, hookRow: HookRow?): Finding? {
        val raw = rawClassOf(declared) ?: return null
        val classification = classifier.classify(raw)

        // (ix) 外側が VALUE・INFRA の容器で、型引数に効果を持ちうる型を含むもの
        if (classification == TypeClass.VALUE || classification == TypeClass.INFRA) {
            typeArgumentTree(declared).firstOrNull { classifier.isEffectful(it, effectful) }?.let {
                return Finding(
                    Headings.WIRING_CONTAINER, label,
                    "規則 (ix): 型引数に効果を持ちうる型 ${it.simpleName} を含む容器 ${raw.simpleName}（中身が記録 fake か確かめられない）",
                )
            }
        }
        return when {
            classification == TypeClass.PORT -> when {
                value == null -> nullEffectful(label, "規則 (i)", raw)
                env.fakes.none { it === value } -> Finding(
                    Headings.WIRING_NOT_RECORDING_FAKE, label,
                    "規則 (i): ${raw.simpleName} の値が、この実行単位の記録 fake でない実物（${value.javaClass.name}）",
                )
                else -> null
            }
            classification == TypeClass.PORT_BACKED -> if (value == null) nullEffectful(label, "規則 (ii)", raw) else null
            classification == TypeClass.FUNCTION -> checkFunction(label, raw, value, env, hookRow)
            classification == TypeClass.POPULATION && raw in effectful ->
                if (value == null) nullEffectful(label, "規則 (iv)", raw) else null
            CoroutineDispatcher::class.java.isAssignableFrom(raw) -> when {
                value == null -> nullEffectful(label, "規則 (v)", raw)
                !onScheduler(value, env) -> foreignScheduler(label, "規則 (v)", "dispatcher")
                else -> null
            }
            CoroutineScope::class.java.isAssignableFrom(raw) -> {
                val dispatcher = (value as? CoroutineScope)?.coroutineContext?.get(ContinuationInterceptor)
                when {
                    value == null -> null
                    dispatcher == null || !onScheduler(dispatcher, env) ->
                        foreignScheduler(label, "規則 (vi)", "scope の context の dispatcher")
                    else -> null
                }
            }
            classification == TypeClass.PLATFORM -> Finding(
                Headings.WIRING_PLATFORM, label, "規則 (vii): PLATFORM（${raw.name}）の field は値に依らず失敗する",
            )
            else -> null // (viii)
        }
    }

    private fun checkFunction(label: String, raw: Class<*>, value: Any?, env: ProbeEnvironment, hookRow: HookRow?): Finding? {
        if (value == null) return nullEffectful(label, "規則 (iii)", raw)
        val registration = env.hooks.registrationOf(value)
            ?: return Finding(
                Headings.WIRING_OUTSIDE_FACTORY, label,
                "規則 (iii): この実行単位の工場が作った関数でない（配線: ${hookRow?.wiring ?: "引数"}）",
            )
        if (hookRow == null) return null
        if (registration.kind != hookRow.kind || registration.shape != hookRow.shape) {
            return Finding(
                Headings.WIRING_CLASS_MISMATCH, label,
                "規則 (iii): 関数の分類・形が hook 表と違う（関数: ${registration.kind}/${registration.shape}、表: ${hookRow.kind}/${hookRow.shape}）",
            )
        }
        return null
    }

    private fun onScheduler(value: Any, env: ProbeEnvironment): Boolean =
        value is TestDispatcher && value.scheduler === env.scheduler

    private fun nullEffectful(label: String, rule: String, raw: Class<*>) =
        Finding(Headings.WIRING_NULL_EFFECTFUL, label, "$rule: 効果を持ちうる型 ${raw.simpleName} の field が null")

    private fun foreignScheduler(label: String, rule: String, what: String) =
        Finding(Headings.WIRING_FOREIGN_SCHEDULER, label, "$rule: $what が、この実行単位の scheduler に載る TestDispatcher でない")

    companion object {
        fun isFunction(type: Class<*>): Boolean = type.name.startsWith(FUNCTION_PREFIX)
    }
}
