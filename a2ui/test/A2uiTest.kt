import dev.azula.a2ui.A2UI_VERSION
import dev.azula.a2ui.A2uiAction
import dev.azula.a2ui.A2uiCodec
import dev.azula.a2ui.A2uiExamples
import dev.azula.a2ui.A2uiSurface
import dev.azula.a2ui.CreateSurface
import dev.azula.a2ui.DICE
import dev.azula.a2ui.DeleteSurface
import dev.azula.a2ui.JsonPointer
import dev.azula.a2ui.UpdateComponents
import dev.azula.a2ui.UpdateDataModel
import dev.azula.a2ui.prop
import dev.azula.a2ui.resolve
import dev.azula.a2ui.resolveBoolean
import dev.azula.a2ui.resolveDouble
import dev.azula.a2ui.resolveString
import dev.azula.a2ui.resolveStringList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class A2uiTest {

    // ---- parse: createSurface ----

    @Test
    fun parseCreateSurface() {
        val line = """{"version":"v0.9.1","createSurface":{"surfaceId":"s1","catalogId":"cat42","sendDataModel":true}}"""
        val msg = A2uiCodec.parse(line)
        assertTrue(msg is CreateSurface, "expected CreateSurface, got $msg")
        assertEquals("s1", msg.surfaceId)
        assertEquals("cat42", msg.catalogId)
        assertEquals(true, msg.sendDataModel)
    }

    @Test
    fun parseCreateSurfaceMinimal() {
        val line = """{"version":"v0.9.1","createSurface":{"surfaceId":"surf"}}"""
        val msg = A2uiCodec.parse(line)
        assertTrue(msg is CreateSurface)
        assertEquals("surf", msg.surfaceId)
        assertNull(msg.catalogId)
        assertEquals(false, msg.sendDataModel)
    }

    // ---- parse: updateComponents ----

    @Test
    fun parseUpdateComponents() {
        val line = """{"version":"v0.9.1","updateComponents":{"surfaceId":"s","components":[{"id":"root","component":"Column","children":["a","b"]},{"id":"a","component":"Text","text":"hello"}]}}"""
        val msg = A2uiCodec.parse(line)
        assertTrue(msg is UpdateComponents, "expected UpdateComponents, got $msg")
        assertEquals("s", msg.surfaceId)
        assertEquals(2, msg.components.size)
        val root = msg.components.first()
        assertEquals("root", root["id"]?.jsonPrimitive?.content)
        assertEquals("Column", root["component"]?.jsonPrimitive?.content)
        val second = msg.components[1]
        assertEquals("a", second["id"]?.jsonPrimitive?.content)
    }

    // ---- parse: updateDataModel ----

    @Test
    fun parseUpdateDataModelInt() {
        val line = """{"version":"v0.9.1","updateDataModel":{"surfaceId":"s","path":"/dice/you","value":3}}"""
        val msg = A2uiCodec.parse(line)
        assertTrue(msg is UpdateDataModel, "expected UpdateDataModel, got $msg")
        assertEquals("s", msg.surfaceId)
        assertEquals("/dice/you", msg.path)
        assertEquals(JsonPrimitive(3), msg.value)
    }

    @Test
    fun parseUpdateDataModelString() {
        val line = """{"version":"v0.9.1","updateDataModel":{"surfaceId":"s","path":"/user/name","value":"alice"}}"""
        val msg = A2uiCodec.parse(line)
        assertTrue(msg is UpdateDataModel)
        assertEquals("alice", (msg as UpdateDataModel).value.jsonPrimitive.content)
    }

    // ---- parse: deleteSurface ----

    @Test
    fun parseDeleteSurface() {
        val line = """{"version":"v0.9.1","deleteSurface":{"surfaceId":"s99"}}"""
        val msg = A2uiCodec.parse(line)
        assertTrue(msg is DeleteSurface, "expected DeleteSurface, got $msg")
        assertEquals("s99", msg.surfaceId)
    }

    // ---- parse: null cases ----

    @Test
    fun parseGarbageReturnsNull() {
        assertNull(A2uiCodec.parse("not json at all!!!"))
    }

    @Test
    fun parseUnrecognizedKeyReturnsNull() {
        assertNull(A2uiCodec.parse("""{"version":"v0.9.1","unknownOp":{"surfaceId":"s"}}"""))
    }

    @Test
    fun parseEmptyObjectReturnsNull() {
        assertNull(A2uiCodec.parse("{}"))
    }

    // ---- JsonPointer.get ----

    @Test
    fun jsonPointerGetRoot() {
        val root = buildJsonObject { put("a", JsonPrimitive(1)) }
        assertEquals(root, JsonPointer.get(root, ""))
    }

    @Test
    fun jsonPointerGetNestedObject() {
        val root = buildJsonObject {
            put("a", buildJsonObject {
                put("b", JsonPrimitive("deep"))
            })
        }
        val result = JsonPointer.get(root, "/a/b")
        assertEquals(JsonPrimitive("deep"), result)
    }

    @Test
    fun jsonPointerGetArrayIndex() {
        val root = buildJsonObject {
            put("items", JsonArray(listOf(JsonPrimitive("x"), JsonPrimitive("y"), JsonPrimitive("z"))))
        }
        assertEquals(JsonPrimitive("y"), JsonPointer.get(root, "/items/1"))
    }

    @Test
    fun jsonPointerGetMissingReturnsNull() {
        val root = buildJsonObject { put("a", JsonPrimitive(1)) }
        assertNull(JsonPointer.get(root, "/missing"))
        assertNull(JsonPointer.get(root, "/a/b/c"))
    }

    @Test
    fun jsonPointerGetOutOfBoundsReturnsNull() {
        val root = buildJsonObject {
            put("arr", JsonArray(listOf(JsonPrimitive(1))))
        }
        assertNull(JsonPointer.get(root, "/arr/5"))
    }

    // ---- JsonPointer.set ----

    @Test
    fun jsonPointerSetCreatesIntermediates() {
        // Setting /dice/you on {} should create {"dice":{"you":3}}
        val empty = JsonObject(emptyMap())
        val result = JsonPointer.set(empty, "/dice/you", JsonPrimitive(3))
        val dice = (result as JsonObject)["dice"] as JsonObject
        assertEquals(JsonPrimitive(3), dice["you"])
    }

    @Test
    fun jsonPointerSetReplacesArrayIndex() {
        val original = buildJsonObject {
            put("arr", JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"), JsonPrimitive("c"))))
        }
        val result = JsonPointer.set(original, "/arr/1", JsonPrimitive("B"))
        val arr = (result as JsonObject)["arr"] as JsonArray
        assertEquals(JsonPrimitive("a"), arr[0])
        assertEquals(JsonPrimitive("B"), arr[1])
        assertEquals(JsonPrimitive("c"), arr[2])
    }

    @Test
    fun jsonPointerSetRoot() {
        val root = JsonObject(emptyMap())
        val replacement = buildJsonObject { put("x", JsonPrimitive(42)) }
        val result = JsonPointer.set(root, "", replacement)
        assertEquals(replacement, result)
    }

    @Test
    fun jsonPointerSetPreservesOtherKeys() {
        val original = buildJsonObject {
            put("keep", JsonPrimitive("me"))
            put("nested", buildJsonObject { put("old", JsonPrimitive(1)) })
        }
        val result = JsonPointer.set(original, "/nested/new", JsonPrimitive(2)) as JsonObject
        assertEquals(JsonPrimitive("me"), result["keep"])
        val nested = result["nested"] as JsonObject
        assertEquals(JsonPrimitive(1), nested["old"])
        assertEquals(JsonPrimitive(2), nested["new"])
    }

    // ---- A2uiBinding ----

    @Test
    fun resolveLiteralString() {
        val dataModel = JsonObject(emptyMap())
        val literal = JsonPrimitive("hello")
        assertEquals(literal, resolve(literal, dataModel))
        assertEquals("hello", resolveString(literal, dataModel))
    }

    @Test
    fun resolveBinding() {
        val dataModel = buildJsonObject {
            put("user", buildJsonObject { put("name", JsonPrimitive("alice")) })
        }
        val binding = buildJsonObject { put("path", JsonPrimitive("/user/name")) }
        assertEquals("alice", resolveString(binding, dataModel))
    }

    @Test
    fun resolveNullReturnsNull() {
        assertNull(resolve(null, JsonObject(emptyMap())))
        assertNull(resolveString(null, JsonObject(emptyMap())))
    }

    @Test
    fun resolveBooleanLiteral() {
        val t = JsonPrimitive(true)
        val f = JsonPrimitive(false)
        assertEquals(true, resolveBoolean(t, JsonObject(emptyMap())))
        assertEquals(false, resolveBoolean(f, JsonObject(emptyMap())))
    }

    @Test
    fun resolveBooleanFromDataModel() {
        val dataModel = buildJsonObject { put("flag", JsonPrimitive(true)) }
        val binding = buildJsonObject { put("path", JsonPrimitive("/flag")) }
        assertEquals(true, resolveBoolean(binding, dataModel))
    }

    @Test
    fun resolveDouble() {
        val dataModel = buildJsonObject { put("score", JsonPrimitive(3.14)) }
        val binding = buildJsonObject { put("path", JsonPrimitive("/score")) }
        val result = resolveDouble(binding, dataModel)
        assertNotNull(result)
        assertTrue(result in 3.13..3.15, "expected ~3.14, got $result")
    }

    @Test
    fun resolveStringList() {
        val dataModel = buildJsonObject {
            put("tags", JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"), JsonPrimitive("c"))))
        }
        val binding = buildJsonObject { put("path", JsonPrimitive("/tags")) }
        assertEquals(listOf("a", "b", "c"), resolveStringList(binding, dataModel))
    }

    @Test
    fun resolveStringListLiteral() {
        val arr = JsonArray(listOf(JsonPrimitive("x"), JsonPrimitive("y")))
        assertEquals(listOf("x", "y"), resolveStringList(arr, JsonObject(emptyMap())))
    }

    @Test
    fun resolveStringListNotArrayReturnsEmpty() {
        assertEquals(emptyList<String>(), resolveStringList(JsonPrimitive("notAList"), JsonObject(emptyMap())))
    }

    @Test
    fun propAccessor() {
        val component = buildJsonObject {
            put("id", JsonPrimitive("btn"))
            put("label", JsonPrimitive("Click me"))
        }
        assertEquals(JsonPrimitive("btn"), prop(component, "id"))
        assertNull(prop(component, "missing"))
    }

    // ---- A2uiSurface ----

    @Test
    fun surfaceApplyUpdateComponents() {
        val surface = A2uiSurface("s1")
        val line = """{"version":"v0.9.1","updateComponents":{"surfaceId":"s1","components":[{"id":"root","component":"Column"},{"id":"child1","component":"Text"}]}}"""
        val msg = A2uiCodec.parse(line)!!
        surface.apply(msg)
        assertNotNull(surface.root())
        assertEquals("Column", surface.root()!!["component"]?.jsonPrimitive?.content)
        assertNotNull(surface.component("child1"))
    }

    @Test
    fun surfaceApplyUpdateDataModel() {
        val surface = A2uiSurface("s1")
        val msg = UpdateDataModel("s1", "/score", JsonPrimitive(42))
        surface.apply(msg)
        assertEquals(JsonPrimitive(42), JsonPointer.get(surface.dataModel, "/score"))
    }

    @Test
    fun surfaceSetData() {
        val surface = A2uiSurface("s2")
        surface.setData("/user/name", JsonPrimitive("bob"))
        assertEquals("bob", (JsonPointer.get(surface.dataModel, "/user/name") as JsonPrimitive).content)
    }

    @Test
    fun surfaceCreateAndDeleteMessagesAreNoOp() {
        val surface = A2uiSurface("s1")
        // These should not throw and should not modify state
        surface.apply(CreateSurface("s1", null, false))
        surface.apply(DeleteSurface("s1"))
        assertTrue(surface.components.isEmpty())
    }

    // ---- A2uiCodec.encodeAction ----

    @Test
    fun encodeAction() {
        val action = A2uiAction(
            name = "roll",
            surfaceId = "s",
            sourceComponentId = "btn",
            context = buildJsonObject { put("value", JsonPrimitive(6)) },
        )
        val json = A2uiCodec.encodeAction(action)
        assertEquals(JsonPrimitive(A2UI_VERSION), json["version"])
        val inner = json["action"] as JsonObject
        assertEquals(JsonPrimitive("roll"), inner["name"])
        assertEquals(JsonPrimitive("s"), inner["surfaceId"])
        assertEquals(JsonPrimitive("btn"), inner["sourceComponentId"])
        val ctx = inner["context"] as JsonObject
        assertEquals(JsonPrimitive(6), ctx["value"])
    }

    @Test
    fun encodeActionDefaultContext() {
        val action = A2uiAction("ping", "surf", "comp1")
        val json = A2uiCodec.encodeAction(action)
        val inner = json["action"] as JsonObject
        assertEquals(JsonObject(emptyMap()), inner["context"])
    }

    // ---- A2uiExamples ----

    @Test
    fun diceSurfaceHasRootAndTitle() {
        val surface = A2uiExamples.diceSurface("test1")
        assertNotNull(surface.root(), "root component should be present")
        val title = surface.component("title")
        assertNotNull(title, "title component should be present")
        assertEquals("AZULA · DICE", title!!["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun diceSurfaceInitialDataModel() {
        val surface = A2uiExamples.diceSurface("test2")
        val you = JsonPointer.get(surface.dataModel, "/dice/you")
        assertEquals(JsonPrimitive("?"), you, "initial /dice/you should be '?'")
        val them = JsonPointer.get(surface.dataModel, "/dice/them")
        assertEquals(JsonPrimitive("?"), them, "initial /dice/them should be '?'")
    }

    @Test
    fun diceSurfaceHandleRollUpdatesGlyphAndResult() {
        val surface = A2uiExamples.diceSurface("test3")
        val action = A2uiAction("roll", surface.surfaceId, "rollBtn")
        A2uiExamples.handleEvent(surface, action)

        val youVal = (JsonPointer.get(surface.dataModel, "/dice/you") as? JsonPrimitive)?.content
        assertNotNull(youVal, "/dice/you should be set after roll")
        assertTrue(youVal in DICE, "/dice/you '$youVal' should be a die glyph from DICE list")

        val result = (JsonPointer.get(surface.dataModel, "/dice/result") as? JsonPrimitive)?.content
        assertNotNull(result, "/dice/result should be set after roll")
        assertTrue(result!!.isNotEmpty(), "/dice/result should be non-empty")
    }
}

