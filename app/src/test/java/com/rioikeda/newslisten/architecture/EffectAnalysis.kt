package com.rioikeda.newslisten.architecture

import java.lang.reflect.Field
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType

internal const val PROJECT_PACKAGE = "com.rioikeda.newslisten"
internal const val FUNCTION_PREFIX = "kotlin.jvm.functions.Function"
internal const val CONTINUATION = "kotlin.coroutines.Continuation"
internal const val COMPOSER = "androidx.compose.runtime.Composer"
private const val DEFAULT_CTOR_MARKER = "kotlin.jvm.internal.DefaultConstructorMarker"

/** 型の生の class。型変数と wildcard は上限（wildcard の `? super X` は X）の class にする。 */
internal fun rawClassOf(type: Type): Class<*>? = when (type) {
    is Class<*> -> type
    is ParameterizedType -> rawClassOf(type.rawType)
    is GenericArrayType -> rawClassOf(type.genericComponentType)?.let { java.lang.reflect.Array.newInstance(it, 0).javaClass }
    is WildcardType -> rawClassOf(type.lowerBounds.firstOrNull() ?: type.upperBounds.first())
    is TypeVariable<*> -> rawClassOf(type.bounds.first())
    else -> null
}

/** 型とその型引数（入れ子を含む）に現れる全 class。配列は要素の class を含む。 */
internal fun typeTree(type: Type): List<Class<*>> = when (type) {
    is Class<*> -> if (type.isArray) listOf(type) + typeTree(type.componentType) else listOf(type)
    is ParameterizedType -> typeTree(type.rawType) + type.actualTypeArguments.flatMap { typeTree(it) }
    is GenericArrayType -> typeTree(type.genericComponentType)
    is WildcardType -> (type.lowerBounds.toList() + type.upperBounds.toList()).flatMap { typeTree(it) }
    is TypeVariable<*> -> type.bounds.mapNotNull { rawClassOf(it) }
    else -> emptyList()
}

/** 型引数だけ（外側の型を除く）の全 class。 */
internal fun typeArgumentTree(type: Type): List<Class<*>> = when (type) {
    is ParameterizedType -> type.actualTypeArguments.flatMap { typeTree(it) }
    is GenericArrayType -> typeTree(type.genericComponentType)
    is Class<*> -> if (type.isArray) typeTree(type.componentType) else emptyList()
    else -> emptyList()
}

/** hook の形と分類を、field の型（`kotlin.jvm.functions.FunctionN<…>`）から求める。型引数が無ければ null。 */
internal fun hookTypeOf(type: Type): Pair<HookShape, HookKind>? {
    val parameterized = type as? ParameterizedType ?: return null
    val raw = rawClassOf(parameterized.rawType) ?: return null
    if (!raw.name.startsWith(FUNCTION_PREFIX)) return null
    val args = parameterized.actualTypeArguments.map { boundOf(it) }
    if (args.isEmpty()) return null
    val rawArgs = args.map { rawClassOf(it)?.name }
    val returnType = args.last()
    fun kindOf(result: Type?): HookKind =
        if (result != null && rawClassOf(result)?.name == "kotlin.Unit") HookKind.WRITE else HookKind.READ
    fun shape(count: Int, suspending: Boolean): HookShape? = when (count) {
        0 -> if (suspending) HookShape.SUSPEND_NO_ARG else HookShape.NO_ARG
        1 -> if (suspending) HookShape.SUSPEND_ONE_ARG else HookShape.ONE_ARG
        else -> null
    }
    // suspend: Function(N+1)<A..., Continuation<R>, Object>
    if (args.size >= 2 && rawArgs[args.size - 2] == CONTINUATION) {
        val continuation = args[args.size - 2] as? ParameterizedType
        val result = continuation?.actualTypeArguments?.firstOrNull()?.let { boundOf(it) }
        return shape(args.size - 2, true)?.let { it to kindOf(result) }
    }
    // Composable: Function(N+2)<A..., Composer, Integer, R>
    if (args.size >= 3 && rawArgs[args.size - 3] == COMPOSER) {
        return shape(args.size - 3, false)?.let { it to kindOf(returnType) }
    }
    return shape(args.size - 1, false)?.let { it to kindOf(returnType) }
}

private fun boundOf(type: Type): Type = if (type is WildcardType) (type.lowerBounds.firstOrNull() ?: type.upperBounds.first()) else type

/** 型 1 つを分類表の規則（port → 母集団 → 追加行 → 関数型 → …）で判定する。 */
internal class TypeClassifier(population: Population, private val catalog: EffectTypeCatalog) {
    val populationClasses: Set<Class<*>> = population.classes.toSet()
    private val portInterfaces: Map<String, String> =
        catalog.ports.flatMap { row -> row.interfaces.map { it to row.port } }.toMap()
    private val added: Map<String, ClassifiedTypeRow> = catalog.addedTypes.associateBy { it.typeName }
    private val cache = HashMap<Class<*>, TypeClass?>()

    fun portNameOf(type: Class<*>): String? = portInterfaces[type.name]

    /** 判定できなければ null（未分類）。 */
    fun classify(type: Class<*>): TypeClass? = cache.getOrPut(type) { decide(type) }

    private fun decide(type: Class<*>): TypeClass? {
        val name = type.name
        if (type.isPrimitive || type.isArray) return TypeClass.VALUE
        // FeedbackPlayer は母集団の型であり port でもある。配線の検査が「記録 fake であること」を要るので port を先に当てる。
        if (name in portInterfaces) return TypeClass.PORT
        if (type in populationClasses) return TypeClass.POPULATION
        added[name]?.let { return it.classification }
        if (name.startsWith(FUNCTION_PREFIX)) return TypeClass.FUNCTION
        if (name in valueNames || containerPrefixes.any { name.startsWith(it) } || name in containerNames) return TypeClass.VALUE
        if (name.startsWith("kotlinx.coroutines.")) return TypeClass.INFRA
        if (name.startsWith("java.util.concurrent.atomic.") || name.startsWith("java.util.concurrent.locks.")) return TypeClass.INFRA
        if (name.startsWith(PROJECT_PACKAGE) && isProjectValue(type)) return TypeClass.VALUE
        if (name.startsWith("java.time.") || name.startsWith("java.text.") || name == "kotlin.text.Regex" ||
            name.startsWith("kotlin.reflect.KProperty") || name.startsWith("kotlinx.serialization.")
        ) return TypeClass.VALUE
        if (name in composeValueNames || composeValuePrefixes.any { name.startsWith(it) }) return TypeClass.VALUE
        if (name.startsWith("android.")) return TypeClass.PLATFORM
        return null
    }

    private fun isProjectValue(type: Class<*>): Boolean {
        if (Throwable::class.java.isAssignableFrom(type)) return true
        val layer = layerOfFile(type) ?: return false
        return layer == Layer.DOMAIN || layer == Layer.READ_MODEL || sourcePath(type)?.startsWith("model/") == true
    }

    private fun sourcePath(type: Class<*>): String? {
        val top = generateSequence(type) { it.enclosingClass }.last()
        val loader = top.classLoader ?: return null
        val source = ClassFileIndex.sourceFileOf(loader, top.name) ?: return null
        val dir = top.packageName.removePrefix(PROJECT_PACKAGE).removePrefix(".").replace('.', '/')
        return if (dir.isEmpty()) source else "$dir/$source"
    }

    private fun layerOfFile(type: Class<*>): Layer? = sourcePath(type)?.let { LayerMap.byFile[it] }

    /** この型が効果を持ちうるか。母集団の class は [effectful]（不動点）に入っているときだけ。 */
    fun isEffectful(type: Class<*>, effectful: Set<Class<*>>): Boolean = when (classify(type)) {
        TypeClass.PORT, TypeClass.PORT_BACKED, TypeClass.FUNCTION, TypeClass.PLATFORM -> true
        TypeClass.POPULATION -> type in effectful
        else -> false
    }

    fun implementsPort(type: Class<*>): Boolean =
        allInterfaces(type).any { it.name in portInterfaces }

    private fun allInterfaces(type: Class<*>): Set<Class<*>> {
        val result = LinkedHashSet<Class<*>>()
        fun visit(c: Class<*>?) {
            if (c == null) return
            c.interfaces.forEach { if (result.add(it)) visit(it) }
            visit(c.superclass)
        }
        visit(type)
        return result
    }

    private companion object {
        val valueNames = setOf(
            "java.lang.String", "java.lang.CharSequence", "java.lang.Object", "kotlin.Unit", "java.lang.Boolean",
            "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long", "java.lang.Float",
            "java.lang.Double", "java.lang.Character",
        )
        val containerNames = setOf(
            "java.util.List", "java.util.Map", "java.util.Set", "java.util.Collection", "kotlin.Lazy", "kotlin.enums.EnumEntries",
        )
        val containerPrefixes = listOf("kotlin.collections.")
        val composeValueNames = setOf(
            "androidx.compose.material3.ColorScheme", "androidx.compose.material3.Typography", "androidx.compose.material3.Shapes",
        )
        val composeValuePrefixes = listOf("androidx.compose.ui.text.", "androidx.compose.ui.graphics.", "androidx.compose.ui.unit.")
    }
}

/** 母集団ファイルの class が持つ、型を宣言する場所（field・構築子の引数）。 */
internal data class TypeSite(val owner: Class<*>, val place: String, val type: Type)

internal fun declaredSites(type: Class<*>): List<TypeSite> {
    val fields = type.declaredFields.map { TypeSite(type, "field ${it.name}", it.genericType) }
    val constructors = type.declaredConstructors
        .filterNot { it.isSynthetic || it.parameterTypes.lastOrNull()?.name == DEFAULT_CTOR_MARKER }
        .flatMap { constructor ->
            val generic = constructor.genericParameterTypes
            val raw = constructor.parameterTypes
            // 入れ子の enum の構築子などは generic の引数の個数が違うので、raw の型で補う。
            val types: List<Type> = if (generic.size == raw.size) generic.toList() else raw.toList()
            types.mapIndexed { index, t -> TypeSite(type, "構築子の引数 #${index + 1}", t) }
        }
    return fields + constructors
}

object EffectfulTypes {
    /** 効果を持ちうる型の不動点（母集団ファイルの class のうち、効果を持ちうるもの）。 */
    fun fixedPoint(population: Population, catalog: EffectTypeCatalog): Set<Class<*>> =
        fixedPoint(TypeClassifier(population, catalog))

    internal fun fixedPoint(classifier: TypeClassifier): Set<Class<*>> {
        val effectful = LinkedHashSet<Class<*>>()
        var changed = true
        while (changed) {
            changed = false
            for (type in classifier.populationClasses) {
                if (type in effectful) continue
                if (makesEffectful(type, classifier, effectful)) {
                    effectful += type
                    changed = true
                }
            }
        }
        return effectful
    }

    private fun makesEffectful(type: Class<*>, classifier: TypeClassifier, effectful: Set<Class<*>>): Boolean {
        if (classifier.classify(type) == TypeClass.PORT) return true
        if (classifier.implementsPort(type)) return true
        if (declaredSites(type).any { site -> typeTree(site.type).any { classifier.isEffectful(it, effectful) } }) return true
        val abstractType = type.isInterface || Modifier.isAbstract(type.modifiers)
        return abstractType && effectful.any { it != type && type.isAssignableFrom(it) }
    }

    /** `STRUCTURAL` を名乗れない理由。無ければ null。 */
    internal fun whyNotStructural(
        method: java.lang.reflect.Method,
        parameterTypes: List<Type>,
        classifier: TypeClassifier,
        effectful: Set<Class<*>>,
    ): String? {
        val declaring = method.declaringClass
        if (declaring in effectful) {
            val via = declaredSites(declaring).firstNotNullOfOrNull { site ->
                typeTree(site.type).firstOrNull { classifier.isEffectful(it, effectful) }?.let { "${site.place}: ${it.simpleName}" }
            } ?: "port の interface を実装している"
            return "宣言する型 ${OperationEnumerator.typeName(declaring)} が効果を持ちうる型（$via）"
        }
        parameterTypes.forEachIndexed { index, t ->
            typeTree(t).firstOrNull { classifier.isEffectful(it, effectful) }?.let {
                return "引数 #${index + 1} の型 ${it.simpleName} が効果を持ちうる型（${classifier.classify(it)}）"
            }
        }
        return null
    }
}

/** 分類表（port 表・型の分類・hook 表）の完全性。 */
object CatalogVerifier {

    /** main の最上位の型の名前（package を除く）。余分な hook の行が、実在しない型を指すかの判定に使う。 */
    private val mainTopLevelTypes: Set<String> by lazy {
        val root = com.rioikeda.newslisten.feed.FeedViewModel::class.java.protectionDomain!!.codeSource!!.location
        ClassFileIndex.topLevelClasses(root, PROJECT_PACKAGE).map { it.binaryName.substringAfterLast('.') }.toSet()
    }

    fun verify(population: Population, catalog: EffectTypeCatalog): List<Finding> {
        val classifier = TypeClassifier(population, catalog)
        return unclassifiedTypes(population, classifier) + portOperations(catalog) + hooks(population, catalog)
    }

    private fun unclassifiedTypes(population: Population, classifier: TypeClassifier): List<Finding> {
        val findings = LinkedHashMap<Pair<String, String>, Finding>()
        fun check(owner: String, place: String, type: Type) {
            typeTree(type).filter { it.name != CONTINUATION && classifier.classify(it) == null }.forEach { unknown ->
                findings.getOrPut(unknown.name to "$owner $place") {
                    Finding(
                        Headings.UNCLASSIFIED_TYPE,
                        unknown.name.removePrefix(unknown.packageName + "."),
                        "${unknown.name}（$owner の $place）。分類表の規則に当たらない",
                    )
                }
            }
        }
        for (type in population.classes) {
            val owner = OperationEnumerator.typeName(type)
            declaredSites(type).forEach { check(owner, it.place, it.type) }
        }
        for (operation in population.operations) {
            operation.parameterTypes.forEachIndexed { index, t -> check(operation.id, "引数 #${index + 1}", t) }
        }
        return findings.values.toList()
    }

    private fun portOperations(catalog: EffectTypeCatalog): List<Finding> = catalog.ports.flatMap { row ->
        val failures = mutableListOf<Finding>()
        val names = mutableSetOf<String>()
        row.interfaces.forEach { name ->
            val type = try {
                Class.forName(name)
            } catch (e: Throwable) {
                failures += Finding(Headings.UNCLASSIFIED_PORT_OPERATION, row.port, "port の interface $name を読めない")
                return@forEach
            }
            type.methods.filter { Modifier.isAbstract(it.modifiers) && !it.isSynthetic }.forEach { names += it.name }
        }
        val listed = row.writes + row.sequenceReads + row.propertyReads
        val overlap = (row.writes intersect row.sequenceReads) + (row.writes intersect row.propertyReads) +
            (row.sequenceReads intersect row.propertyReads)
        failures +
            (names - listed).sorted().map { Finding(Headings.UNCLASSIFIED_PORT_OPERATION, "${row.port}#$it", "書込・列の読み・property の読みのどれにも載っていない") } +
            (listed - names).sorted().map { Finding(Headings.SURPLUS_CATALOG_ROW, "${row.port}#$it", "interface に実在しない操作") } +
            overlap.sorted().map { Finding(Headings.SURPLUS_CATALOG_ROW, "${row.port}#$it", "書込・列の読み・property の読みが排他でない") }
    }

    private fun hooks(population: Population, catalog: EffectTypeCatalog): List<Finding> {
        val actual = linkedMapOf<String, Field>()
        population.classes.forEach { type ->
            type.declaredFields.filter { it.type.name.startsWith(FUNCTION_PREFIX) }.forEach { field ->
                actual["${OperationEnumerator.typeName(type)}#${field.name}"] = field
            }
        }
        val listed = catalog.hooks.associateBy { it.ownerField }
        val findings = mutableListOf<Finding>()
        (actual.keys - listed.keys).forEach {
            findings += Finding(Headings.UNREGISTERED_HOOK, it, "FUNCTION 型の field が hook 表に無い")
        }
        val populationTypes = population.classes.map { OperationEnumerator.typeName(it) }.toSet()
        (listed.keys - actual.keys).forEach { ownerField ->
            val owner = ownerField.substringBefore('#')
            // 母集団にその型があるのに field が無い、または main のどこにもその型が無い行だけが余り。
            // 母集団の外（別の世界の fixture など）の型の行は、その世界の検査が見る。
            if (owner in populationTypes || owner.substringBefore('.') !in mainTopLevelTypes) {
                findings += Finding(Headings.SURPLUS_CATALOG_ROW, ownerField, "hook 表の行に対応する field が母集団に無い")
            }
        }
        listed.forEach { (key, row) ->
            val field = actual[key] ?: return@forEach
            if (row.kind == HookKind.INTERNAL && row.reason.isNullOrBlank()) {
                findings += Finding(Headings.UNREGISTERED_HOOK, key, "INTERNAL の行は reason が必須")
            }
            if (row.wiring == HookWiring.SETTER && row.kind != HookKind.WRITE) {
                findings += Finding(Headings.UNREGISTERED_HOOK, key, "SETTER の行は WRITE に限る")
            }
            val computed = hookTypeOf(field.genericType) ?: return@forEach
            if (computed.first != row.shape) {
                findings += Finding(Headings.UNREGISTERED_HOOK, key, "形が field の型と一致しない（表: ${row.shape}、型: ${computed.first}）")
            }
            if (row.kind != HookKind.INTERNAL && computed.second != row.kind) {
                findings += Finding(Headings.UNREGISTERED_HOOK, key, "分類が field の型と一致しない（表: ${row.kind}、型: ${computed.second}）")
            }
        }
        return findings
    }
}
