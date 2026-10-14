package com.jadegenesis.mobile.tools

import android.content.Context
import android.util.AtomicFile
import com.jadegenesis.mobile.night.canonical
import com.jadegenesis.mobile.night.researchSha
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.nio.charset.CodingErrorAction
import java.util.UUID
import com.jadegenesis.mobile.model.ToolInfo

/** Restricted learned recipe. No generated code, paths, imports or process primitives. */
internal data class JsonRecipe(val indent: Int, val from: String? = null, val to: String? = null) {
    fun manifest() = JSONObject().put("language", "JADE_JSON_RECIPE_V1").put("indent", indent)
        .put("rename_from", from ?: JSONObject.NULL).put("rename_to", to ?: JSONObject.NULL)
    val hash: String get() = researchSha(canonical(manifest()).toByteArray())
    companion object {
        fun parse(json: JSONObject): JsonRecipe {
            require(json.keys().asSequence().toSet() == setOf("language", "indent", "rename_from", "rename_to"))
            require(json.getString("language") == "JADE_JSON_RECIPE_V1" && json.opt("indent") is Int)
            val indent = json.getInt("indent"); require(indent in setOf(0, 2, 4))
            val from = if (json.isNull("rename_from")) null else json.getString("rename_from")
            val to = if (json.isNull("rename_to")) null else json.getString("rename_to")
            require((from == null) == (to == null) && (from == null || (from.isNotEmpty() && from.length <= 80 && to!!.isNotEmpty() && to.length <= 80 && from != to)))
            return JsonRecipe(indent, from, to)
        }
    }
}

internal sealed interface JsonTree
internal data class JsonObjectTree(val entries: List<Pair<String, JsonTree>>) : JsonTree
internal data class JsonArrayTree(val values: List<JsonTree>) : JsonTree
internal data class JsonAtom(val token: String) : JsonTree

/** Strict parsing preserves number lexemes, including large integers and decimals. */
internal class BoundedJsonParser(private val text: String) {
    private var offset = 0
    private var count = 0
    fun parse(): JsonTree {
        require(text.toByteArray().size in 1..16_384) { "JSON limité à 16 Ko" }
        val result = value(0); whitespace(); require(offset == text.length) { "Texte après le JSON" }; return result
    }
    private fun whitespace() { while (offset < text.length && text[offset] in " \t\r\n") offset++ }
    private fun consume(char: Char) { whitespace(); require(offset < text.length && text[offset++] == char) { "JSON incomplet ou invalide" } }
    private fun string(): String {
        whitespace(); val start = offset; consume('"')
        while (offset < text.length) {
            val c = text[offset++]
            if (c == '"') return text.substring(start, offset)
            require(c.code >= 32) { "Caractère de contrôle dans une chaîne" }
            if (c == '\\') {
                require(offset < text.length)
                val escape = text[offset++]
                if (escape == 'u') {
                    require(offset + 4 <= text.length && text.substring(offset, offset + 4).all { it in "0123456789abcdefABCDEF" })
                    offset += 4
                } else require(escape in "\"\\/bfnrt") { "Échappement JSON invalide" }
            }
        }
        error("Chaîne JSON non terminée")
    }
    private fun value(depth: Int): JsonTree {
        require(depth <= 24 && ++count <= 2048) { "Structure JSON trop profonde ou trop grande" }
        whitespace(); require(offset < text.length)
        return when (text[offset]) {
            '{' -> {
                consume('{'); whitespace(); val entries = mutableListOf<Pair<String, JsonTree>>(); val keys = mutableSetOf<String>()
                if (offset < text.length && text[offset] != '}') do {
                    val key = JSONTokener(string()).nextValue() as String
                    require(Charsets.UTF_8.newEncoder().canEncode(key)) { "Clé Unicode invalide" }
                    require(keys.add(key)) { "Clé JSON dupliquée" }
                    consume(':'); entries += key to value(depth + 1); whitespace()
                    if (offset >= text.length || text[offset] != ',') break
                    offset++; whitespace(); require(offset < text.length && text[offset] != '}')
                } while (true)
                consume('}'); JsonObjectTree(entries)
            }
            '[' -> {
                consume('['); whitespace(); val values = mutableListOf<JsonTree>()
                if (offset < text.length && text[offset] != ']') do {
                    values += value(depth + 1); whitespace()
                    if (offset >= text.length || text[offset] != ',') break
                    offset++; whitespace(); require(offset < text.length && text[offset] != ']')
                } while (true)
                consume(']'); JsonArrayTree(values)
            }
            '"' -> JsonAtom(string())
            else -> {
                val start = offset
                while (offset < text.length && text[offset] !in " \t\r\n,]}") offset++
                val token = text.substring(start, offset)
                require(token in setOf("true", "false", "null") || Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(token)) { "Valeur JSON invalide" }
                JsonAtom(token)
            }
        }
    }
}

internal object JsonRecipeRuntime {
    fun run(recipe: JsonRecipe, input: String): String {
        JsonRecipe.parse(recipe.manifest())
        val parsed = BoundedJsonParser(input).parse()
        val transformed = if (recipe.from == null) parsed else {
            require(parsed is JsonObjectTree) { "Le renommage demande un objet JSON principal" }
            require(parsed.entries.none { it.first == recipe.from } || parsed.entries.none { it.first == recipe.to }) { "La clé de destination existe déjà" }
            JsonObjectTree(parsed.entries.map { (key, value) -> (if (key == recipe.from) recipe.to!! else key) to value })
        }
        val result = render(transformed, recipe.indent, 0)
        require(result.toByteArray().size <= 65_536) { "Sortie trop grande" }
        return result
    }
    private fun render(tree: JsonTree, indent: Int, depth: Int): String {
        val padding = " ".repeat(indent * depth)
        val next = " ".repeat(indent * (depth + 1))
        return when (tree) {
            is JsonAtom -> tree.token
            is JsonObjectTree -> {
                if (tree.entries.isEmpty()) "{}" else {
                    val content = tree.entries.map { (key, value) -> JSONObject.quote(key) + (if (indent == 0) ":" else ": ") + render(value, indent, depth + 1) }
                    if (indent == 0) content.joinToString(",", "{", "}") else content.joinToString(",\n$next", "{\n$next", "\n$padding}")
                }
            }
            is JsonArrayTree -> {
                if (tree.values.isEmpty()) "[]" else {
                    val content = tree.values.map { render(it, indent, depth + 1) }
                    if (indent == 0) content.joinToString(",", "[", "]") else content.joinToString(",\n$next", "[\n$next", "\n$padding]")
                }
            }
        }
    }
}

internal data class JsonDemonstration(val before: String, val after: String)

internal object JsonRecipeLearner {
    /** Only TRAIN is visible here; the held-out case is checked after freezing. */
    fun propose(train: List<JsonDemonstration>): JsonRecipe {
        require(train.size == 2) { "Deux exemples d'apprentissage sont nécessaires" }
        val pairs = train.map { BoundedJsonParser(it.before).parse() to BoundedJsonParser(it.after).parse() }
        val renames = mutableSetOf<Pair<String, String>>()
        pairs.forEach { (before, after) ->
            if (before is JsonObjectTree && after is JsonObjectTree) {
                val removed = before.entries.map { it.first }.toSet() - after.entries.map { it.first }.toSet()
                val added = after.entries.map { it.first }.toSet() - before.entries.map { it.first }.toSet()
                if (removed.size == 1 && added.size == 1) renames += removed.single() to added.single()
            }
        }
        val candidates = listOf(0, 2, 4).flatMap { indent -> listOf(JsonRecipe(indent)) + renames.map { JsonRecipe(indent, it.first, it.second) } }
        val matching = candidates.filter { candidate -> train.all { example -> runCatching { JsonRecipeRuntime.run(candidate, example.before) == example.after }.getOrDefault(false) } }
        require(matching.size == 1) { "Exemples incompatibles ou ambigus : préciser la transformation" }
        return matching.single()
    }
}

internal class JsonFileLearningWorkshop(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "json_file_workshop_v1")
    private val stateFile = File(directory, "state.json")
    companion object { private val lock = Any() }
    private fun read(file: File, max: Int): ByteArray = file.inputStream().use { input -> boundedRead(input, max) }
    private fun boundedRead(input: java.io.InputStream, max: Int): ByteArray {
        val buffer = ByteArray(max + 1); var length = 0
        while (length < buffer.size) { val n = input.read(buffer, length, buffer.size - length); if (n < 0) break; length += n }
        require(length <= max); return buffer.copyOf(length)
    }
    private fun load(): JSONObject {
        if (!stateFile.exists() && !File(stateFile.path + ".bak").exists()) return JSONObject().put("schema", "jade.json_file_workshop.v1").put("events", JSONArray())
        val raw = AtomicFile(stateFile).openRead().use { boundedRead(it, 300_000) }
        require(raw.size <= 300_000)
        val root = JSONObject(raw.toString(Charsets.UTF_8)); require(root.getString("schema") == "jade.json_file_workshop.v1")
        val rows = root.getJSONArray("events"); require(rows.length() <= 256)
        var previous = "0".repeat(64)
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i); val body = JSONObject(row.toString()).apply { remove("sha256") }
            require(row.getInt("sequence") == i + 1 && row.getString("previous_sha256") == previous && row.getString("sha256") == researchSha(canonical(body).toByteArray())) { "Journal d'outils altéré" }
            previous = row.getString("sha256")
        }
        root.optJSONObject("candidate")?.let { require(JsonRecipe.parse(it.getJSONObject("recipe")).hash == it.getString("tool_sha256")) }
        root.optJSONObject("retained")?.let { require(JsonRecipe.parse(it.getJSONObject("recipe")).hash == it.getString("tool_sha256")) }
        return root
    }
    private fun save(root: JSONObject) {
        check(directory.isDirectory || directory.mkdirs())
        val text = root.toString().toByteArray(); require(text.size <= 300_000)
        val atomic = AtomicFile(stateFile); val out = atomic.startWrite()
        try { out.write(text); atomic.finishWrite(out) } catch (error: Throwable) { atomic.failWrite(out); throw error }
    }
    private fun append(root: JSONObject, kind: String, details: JSONObject = JSONObject()) {
        val rows = root.getJSONArray("events"); require(rows.length() < 256)
        details.put("kind", kind).put("at_ms", System.currentTimeMillis()).put("sequence", rows.length() + 1)
            .put("previous_sha256", if (rows.length() == 0) "0".repeat(64) else rows.getJSONObject(rows.length() - 1).getString("sha256"))
        details.put("sha256", researchSha(canonical(details).toByteArray())); rows.put(details)
    }
    fun state(): JSONObject = synchronized(lock) { load() }
    fun catalog(): ToolInfo? = synchronized(lock) {
        if (load().optJSONObject("retained") == null) null else ToolInfo(
            "learned_json_transform_v1", "Procédure JSON apprise par exemples et validée localement. Transformation d'une copie privée après autorisation exacte, à usage unique.")
    }
    fun executeApprovedFile(): JSONObject = synchronized(lock) {
        val grant = load().getJSONObject("grant")
        require(grant.getJSONObject("plan").getString("action") == "TRANSFORM_JSON_PRIVATE_COPY")
        execute(grant.getString("id"))
    }
    fun propose(train: List<JsonDemonstration>): JSONObject = synchronized(lock) {
        val recipe = JsonRecipeLearner.propose(train)
        val root = load(); val candidate = JSONObject().put("recipe", recipe.manifest()).put("tool_sha256", recipe.hash)
            .put("train_sha256", researchSha(train.joinToString("\u0000") { it.before + "\u0000" + it.after }.toByteArray())).put("status", "CANDIDATE")
        root.put("candidate", candidate); root.remove("grant")
        append(root, "CANDIDATE_CREATED", JSONObject().put("tool_sha256", recipe.hash).put("generator", "BOUNDED_EXAMPLE_SEARCH")); save(root); candidate
    }
    fun validationPlan(example: JsonDemonstration): JSONObject = synchronized(lock) {
        BoundedJsonParser(example.before).parse(); BoundedJsonParser(example.after).parse()
        val candidate = load().getJSONObject("candidate")
        JSONObject().put("action", "VALIDATE_AND_RETAIN_JSON_TOOL").put("tool_sha256", candidate.getString("tool_sha256"))
            .put("before", example.before).put("after", example.after).put("scope", "PIXEL_PRIVATE_COPY_ONLY")
    }
    fun importInput(bytes: ByteArray): JSONObject = synchronized(lock) {
        require(bytes.size in 1..16_384)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        BoundedJsonParser(text).parse()
        val hash = researchSha(bytes); check(directory.isDirectory || directory.mkdirs())
        val path = File(directory, "input-$hash.json")
        if (path.exists()) require(researchSha(read(path, 16_384)) == hash) else path.outputStream().use { it.write(bytes) }
        val root = load(); val input = JSONObject().put("sha256", hash).put("file", path.name)
        root.put("input", input); root.remove("grant"); append(root, "INPUT_COPY_IMPORTED", JSONObject().put("input_sha256", hash)); save(root); input
    }
    fun filePlan(): JSONObject = synchronized(lock) {
        val root = load(); val tool = root.optJSONObject("retained") ?: error("Aucun outil JSON appris et validé")
        val input = root.getJSONObject("input")
        JSONObject().put("action", "TRANSFORM_JSON_PRIVATE_COPY").put("tool_sha256", tool.getString("tool_sha256"))
            .put("input_sha256", input.getString("sha256")).put("scope", "PIXEL_PRIVATE_COPY_ONLY")
    }
    fun authorize(plan: JSONObject): JSONObject = synchronized(lock) {
        val root = load()
        val current = if (plan.getString("action") == "VALIDATE_AND_RETAIN_JSON_TOOL") validationPlan(JsonDemonstration(plan.getString("before"), plan.getString("after"))) else filePlan()
        require(canonical(plan) == canonical(current)) { "Plan modifié : nouvelle autorisation nécessaire" }
        val grant = JSONObject().put("id", UUID.randomUUID().toString()).put("plan", plan)
            .put("plan_sha256", researchSha(canonical(plan).toByteArray())).put("expires_at_ms", System.currentTimeMillis() + 900_000).put("status", "GRANTED")
        root.put("grant", grant); append(root, "AUTHORIZED", JSONObject().put("authorization_id", grant.getString("id")).put("plan_sha256", grant.getString("plan_sha256"))); save(root); grant
    }
    fun revoke(): Unit = synchronized(lock) {
        val root = load(); val grant = root.getJSONObject("grant"); require(grant.getString("status") == "GRANTED")
        grant.put("status", "REVOKED"); append(root, "REVOKED", JSONObject().put("authorization_id", grant.getString("id"))); save(root)
    }
    fun execute(id: String): JSONObject = synchronized(lock) {
        val root = load(); val grant = root.getJSONObject("grant")
        require(grant.getString("id") == id && grant.getString("status") == "GRANTED" && System.currentTimeMillis() < grant.getLong("expires_at_ms")) { "Autorisation expirée, révoquée ou consommée" }
        val plan = grant.getJSONObject("plan")
        val current = if (plan.getString("action") == "VALIDATE_AND_RETAIN_JSON_TOOL") validationPlan(JsonDemonstration(plan.getString("before"), plan.getString("after"))) else filePlan()
        require(canonical(plan) == canonical(current) && grant.getString("plan_sha256") == researchSha(canonical(plan).toByteArray()))
        require(root.getJSONArray("events").length() <= 253)
        require(root.toString().toByteArray().size <= 150_000) { "Journal trop plein pour tracer la sortie" }
        grant.put("status", "STARTED"); append(root, "STARTED", JSONObject().put("authorization_id", id)); save(root)
        val previousRetained = root.optJSONObject("retained")
        try {
            val result = if (plan.getString("action") == "VALIDATE_AND_RETAIN_JSON_TOOL") {
                val candidate = root.getJSONObject("candidate"); val recipe = JsonRecipe.parse(candidate.getJSONObject("recipe"))
                val output = JsonRecipeRuntime.run(recipe, plan.getString("before"))
                require(output == plan.getString("after")) { "Le cas séparé ne passe pas : outil non conservé" }
                val retained = JSONObject(candidate.toString()).put("status", "VALIDATED_LOCALLY").put("validation_sha256", grant.getString("plan_sha256"))
                root.put("retained", retained)
                JSONObject().put("status", "TOOL_RETAINED").put("tool_sha256", recipe.hash).put("independent_attestation", false)
            } else {
                val recipe = JsonRecipe.parse(root.getJSONObject("retained").getJSONObject("recipe"))
                val hash = plan.getString("input_sha256"); require(Regex("[0-9a-f]{64}").matches(hash))
                val inputFile = File(directory, "input-$hash.json"); val bytes = read(inputFile, 16_384)
                require(researchSha(bytes) == hash) { "Fichier modifié après autorisation" }
                val output = JsonRecipeRuntime.run(recipe, bytes.toString(Charsets.UTF_8))
                val destination = File(directory, "output-${UUID.randomUUID()}.json")
                check(destination.createNewFile()); destination.writeText(output, Charsets.UTF_8)
                JSONObject().put("status", "FILE_TRANSFORMED").put("output", output).put("output_file", destination.name)
                    .put("input_sha256", hash).put("output_sha256", researchSha(output.toByteArray())).put("tool_sha256", recipe.hash)
                    .put("selection", "RETAINED_TOOL_AUTO_SELECTED").put("resynthesis_calls", 0).put("original_unchanged", researchSha(read(inputFile, 16_384)) == hash)
            }
            root.put("last_result", result); grant.put("status", "COMPLETED")
            append(root, "COMPLETED", JSONObject().put("authorization_id", id).put("result", result)); save(root); result
        } catch (error: Exception) {
            grant.put("status", "FAILED")
            if (previousRetained == null) root.remove("retained") else root.put("retained", previousRetained)
            append(root, "FAILED", JSONObject().put("authorization_id", id).put("reason", error.message?.take(200))); save(root); throw error
        }
    }
}
