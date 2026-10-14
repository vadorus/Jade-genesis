package com.jadegenesis.mobile

import com.jadegenesis.mobile.tools.*
import com.jadegenesis.mobile.ui.JsonWorkshopDemo
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JsonFileLearningTest {
    @Test fun buildsRecipeFromTrainingExamplesOnly() {
        val recipe = JsonRecipeLearner.propose(JsonWorkshopDemo.train)
        assertEquals(2, recipe.indent); assertEquals("label", recipe.from); assertEquals("nom", recipe.to)
        assertEquals(JsonWorkshopDemo.validation.after, JsonRecipeRuntime.run(recipe, JsonWorkshopDemo.validation.before))
    }
    @Test fun unlearnableAndAmbiguousExamplesRefused() {
        val empty = JsonDemonstration("{}", "{}")
        assertTrue(runCatching { JsonRecipeLearner.propose(listOf(empty, empty)) }.isFailure)
        assertTrue(runCatching { JsonRecipeLearner.propose(listOf(JsonWorkshopDemo.train[0], JsonDemonstration("{\"a\":1}", "{\"a\":2}"))) }.isFailure)
    }
    @Test fun newInputKeepsUnknownNestedFieldsAndPreciseNumbers() {
        val recipe = JsonRecipeLearner.propose(JsonWorkshopDemo.train)
        val input = "{\"label\":\"nouveau\",\"large\":9007199254740993,\"decimal\":1.234567890123456789,\"extra\":{\"a\":[true,null]}}"
        val result = JsonRecipeRuntime.run(recipe, input)
        assertTrue(result.contains("\"nom\": \"nouveau\"")); assertTrue(result.contains("9007199254740993")); assertTrue(result.contains("1.234567890123456789"))
        assertTrue(result.contains("\"extra\"")); assertTrue(result.contains("true")); assertTrue(result.contains("null"))
    }
    @Test fun duplicateInvalidAndTrailingJsonRefused() {
        for (input in listOf("{\"a\":1,\"a\":2}", "{\"a\":1,\"\\u0061\":2}", "{'a':1}", "[1,]", "{} extra", "01", "NaN", "\"bad\\q\""))
            assertTrue(input, runCatching { BoundedJsonParser(input).parse() }.isFailure)
    }
    @Test fun deeplyNestedAndOversizedInputRefused() {
        assertTrue(runCatching { BoundedJsonParser("[".repeat(30) + "0" + "]".repeat(30)).parse() }.isFailure)
        assertTrue(runCatching { BoundedJsonParser("\"" + "x".repeat(16_384) + "\"").parse() }.isFailure)
    }
    @Test fun targetCollisionCannotOverwriteAField() {
        val recipe = JsonRecipeLearner.propose(JsonWorkshopDemo.train)
        assertTrue(runCatching { JsonRecipeRuntime.run(recipe, "{\"label\":1,\"nom\":2}") }.isFailure)
        assertTrue(JsonRecipeRuntime.run(recipe, "{\"nom\":2}").contains("\"nom\": 2"))
    }
    @Test fun arbitraryCommandsOrPermissionsCannotEnterRecipe() {
        val recipe = JsonRecipeLearner.propose(JsonWorkshopDemo.train)
        assertTrue(runCatching { JsonRecipe.parse(recipe.manifest().put("exec", "delete files")) }.isFailure)
        assertTrue(runCatching { JsonRecipe.parse(recipe.manifest().put("language", "python")) }.isFailure)
        assertTrue(runCatching { JsonRecipe.parse(recipe.manifest().put("indent", 1000)) }.isFailure)
    }
    @Test fun pathStringsRemainJsonData() {
        val output = JsonRecipeRuntime.run(JsonRecipe(2), "{\"file\":\"../../personal.json\",\"cmd\":\"rm -rf\"}")
        assertTrue(output.contains("../../personal.json")); assertTrue(output.contains("rm -rf"))
    }
}
