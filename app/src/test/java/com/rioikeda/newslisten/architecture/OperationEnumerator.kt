package com.rioikeda.newslisten.architecture

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Type
import java.net.URL

/** 列挙の失敗。path・class 名・メソッド名を含む（空集合を返さない）。 */
class EnumerationFailure(message: String) : RuntimeException(message)

/**
 * 公開操作 1 件。`id` は `<型名>#<正規化した JVM のメソッド名>`、`signature` は引数の単純名（suspend は先頭に `suspend `）。
 * `(id, signature)` が集合の中で一意。
 */
data class PublicOperation(
    val id: String,
    val signature: String,
    val typeName: String,
    val path: String,
    val parameterTypes: List<Type>,
    val method: Method,
)

/** 母集団。`classesByFile` は母集団ファイルごとの全 class（非 public・入れ子・companion を含む）。 */
data class Population(
    val operations: List<PublicOperation>,
    val classesByFile: Map<String, List<Class<*>>>,
) {
    val classes: List<Class<*>> get() = classesByFile.values.flatten()
}

object OperationEnumerator {

    /** 資源・build 設定から生成された、母集団の対応表に載らない class の名前（除外はこれだけ）。 */
    private val generatedJavaClasses = setOf("BuildConfig", "R")


    /** class の根から母集団と公開操作を列挙する。層の値で母集団を狭めない。 */
    fun enumerate(classRoot: URL, packagePrefix: String, layerOf: Map<String, Layer>, loader: ClassLoader): Population {
        val filesToClasses = classesBySourcePath(classRoot, packagePrefix, layerOf)

        val populationFiles = layerOf.filterValues { it == Layer.APPLICATION || it == Layer.PRESENTATION }.keys.sorted()
        if (populationFiles.isEmpty()) {
            throw EnumerationFailure("母集団ファイルが 0 件: layerOf に APPLICATION か PRESENTATION の行が無い")
        }

        val classesByFile = linkedMapOf<String, List<Class<*>>>()
        val topLevelByFile = linkedMapOf<String, List<Class<*>>>()
        for (file in populationFiles) {
            val names = filesToClasses[file].orEmpty()
            if (names.isEmpty()) {
                throw EnumerationFailure("母集団ファイル $file に対応する class が 0 件（根: $classRoot、prefix: $packagePrefix）")
            }
            val topLevel = names.map { load(it, loader) }
            topLevelByFile[file] = topLevel
            classesByFile[file] = topLevel.flatMap { allClasses(it) }
        }

        val operations = mutableListOf<PublicOperation>()
        val typeOwners = linkedMapOf<String, MutableList<Pair<String, Class<*>>>>()
        for ((file, topLevel) in topLevelByFile) {
            for (top in topLevel) {
                for (type in publicTypes(top)) {
                    typeOwners.getOrPut(typeName(type)) { mutableListOf() }.add(file to type)
                    operations += operationsOf(type, file)
                }
            }
        }
        typeOwners.filterValues { it.size > 1 }.entries.firstOrNull()?.let { (name, owners) ->
            throw EnumerationFailure(
                "型名 $name が母集団の中で重複している: " + owners.joinToString { (file, cls) -> "$file の ${cls.name}" },
            )
        }

        val byKey = operations.groupBy { it.id to it.signature }
        byKey.filterValues { it.size > 1 }.entries.firstOrNull()?.let { (key, same) ->
            throw EnumerationFailure(
                "同じ (操作 ID, signature) になる公開メソッドが複数ある: ${key.first} ${key.second} — " +
                    same.joinToString { "${it.method.declaringClass.name}#${it.method.name}（${it.path}）" },
            )
        }

        return Population(
            operations = operations.sortedWith(compareBy({ it.id }, { it.signature })),
            classesByFile = classesByFile,
        )
    }

    /** 根の下の最上位の class を、`SourceFile` 属性と package から作る path ごとにまとめる。LayerMap に行が無い class は失敗。 */
    private fun classesBySourcePath(classRoot: URL, packagePrefix: String, layerOf: Map<String, Layer>): Map<String, List<String>> {
        val result = linkedMapOf<String, MutableList<String>>()
        for (entry in ClassFileIndex.topLevelClasses(classRoot, packagePrefix)) {
            val simpleName = entry.binaryName.substringAfterLast('.')
            val source = entry.sourceFile
            if (source == null || source.endsWith(".java")) {
                // 資源・build 設定から生成された class（R・BuildConfig）だけを除く。名前を列挙し、それ以外は失敗にする。
                if (simpleName in generatedJavaClasses) continue
                throw EnumerationFailure(
                    "class ${entry.binaryName} は .kt の SourceFile を持たない（SourceFile=$source）。除外の名前は $generatedJavaClasses だけ（根: $classRoot）",
                )
            }
            val pkg = entry.binaryName.substringBeforeLast('.', "")
            val relativeDir = pkg.removePrefix(packagePrefix).removePrefix(".").replace('.', '/')
            val path = if (relativeDir.isEmpty()) source else "$relativeDir/$source"
            if (path !in layerOf) {
                throw EnumerationFailure("class ${entry.binaryName} の path $path に LayerMap の行が無い")
            }
            result.getOrPut(path) { mutableListOf() }.add(entry.binaryName)
        }
        return result
    }

    private fun load(name: String, loader: ClassLoader): Class<*> = try {
        Class.forName(name, false, loader)
    } catch (e: Throwable) {
        throw EnumerationFailure("class $name を読めない: ${e.javaClass.name}: ${e.message}")
    }

    /** 最上位の class とその declaredClasses（再帰。合成の class を除く）。 */
    private fun allClasses(top: Class<*>): List<Class<*>> {
        val declared = try {
            top.declaredClasses.filterNot { it.isSynthetic }
        } catch (e: Throwable) {
            throw EnumerationFailure("class ${top.name} の入れ子の class を読めない: ${e.javaClass.name}: ${e.message}")
        }
        return listOf(top) + declared.flatMap { allClasses(it) }
    }

    /** JVM で public な最上位の class と、その public な declaredClasses（再帰。`DefaultImpls` を除く）。 */
    private fun publicTypes(top: Class<*>): List<Class<*>> {
        if (!Modifier.isPublic(top.modifiers)) return emptyList()
        val nested = top.declaredClasses
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && it.simpleName != "DefaultImpls" }
            .flatMap { publicTypes(it) }
        return listOf(top) + nested
    }

    internal fun typeName(type: Class<*>): String = type.name.removePrefix(type.packageName + ".").replace('$', '.')

    private fun operationsOf(type: Class<*>, path: String): List<PublicOperation> {
        val methods = try {
            type.declaredMethods.toList()
        } catch (e: Throwable) {
            throw EnumerationFailure("class ${type.name}（$path）のメソッドを読めない: ${e.javaClass.name}: ${e.message}")
        }
        val isDataClass = methods.any { it.name == "copy\$default" }
        return methods
            .filter { Modifier.isPublic(it.modifiers) }
            .filterNot { it.isSynthetic || it.isBridge || it.name.contains('$') }
            .filterNot { isGenerated(it, isDataClass, type) }
            .filterNot { m -> m.parameterTypes.any { it.name == COMPOSER } }
            .map { method ->
                if (method.name.contains("-impl")) {
                    throw EnumerationFailure("公開メソッド ${type.name}#${method.name}（$path）は名前に -impl を含む（value class の生成メソッドは除外せず失敗にする）")
                }
                val params = method.parameterTypes.toList()
                val suspending = params.lastOrNull()?.name == CONTINUATION
                val visible = if (suspending) params.dropLast(1) else params
                val generic = method.genericParameterTypes.toList().let { if (suspending && it.isNotEmpty()) it.dropLast(1) else it }
                PublicOperation(
                    id = typeName(type) + "#" + normalizedName(method),
                    signature = (if (suspending) "suspend " else "") + visible.joinToString(",", "(", ")") { simpleName(it) },
                    typeName = typeName(type),
                    path = path,
                    parameterTypes = generic,
                    method = method,
                )
            }
    }

    private fun isGenerated(method: Method, isDataClass: Boolean, type: Class<*>): Boolean {
        val n = method.name
        val p = method.parameterCount
        if (n == "equals" && p == 1 || n == "hashCode" && p == 0 || n == "toString" && p == 0) return true
        if (isDataClass && (n == "copy" || (n.startsWith("component") && n.drop(9).toIntOrNull() != null && p == 0))) return true
        if (type.isEnum && (n == "values" && p == 0 || n == "valueOf" && p == 1 || n == "getEntries" && p == 0)) return true
        return false
    }

    /** JVM のメソッド名の、最初の `-` より後ろ（inline class の hash）を落とした名前。 */
    internal fun normalizedName(method: Method): String = method.name.substringBefore('-')

    /** package を除いた binary 名。入れ子は `.`、primitive は JVM の名前、配列は要素の単純名に `[]`。 */
    internal fun simpleName(type: Class<*>): String =
        if (type.isArray) simpleName(type.componentType) + "[]" else typeName(type)
}
