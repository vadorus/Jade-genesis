package com.jadegenesis.mobile.cognitive

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CognitiveReviewFormatTest {
    @Test fun validReviewIsRecognized() {
        assertTrue(isStructuredCognitiveReview(JSONObject("""{"verdict":"ok","note":"Contrôle effectué","confidence":0.9}""")))
    }

    @Test fun arbitraryJsonOrInvalidFieldsAreNotSuccessfulReviews() {
        for (raw in listOf(
            "{}",
            """{"verdict":"ok"}""",
            """{"verdict":"invented","note":"x","confidence":0.5}""",
            """{"verdict":"ok","note":"","confidence":0.5}""",
            """{"verdict":"ok","note":"x","confidence":"0.5"}""",
            """{"verdict":"ok","note":"x","confidence":2}"""
        )) assertFalse(raw, isStructuredCognitiveReview(JSONObject(raw)))
    }
}
