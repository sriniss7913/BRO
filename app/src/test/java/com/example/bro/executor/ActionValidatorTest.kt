package com.example.bro.executor

import com.example.bro.model.Action
import com.example.bro.model.ActionType
import com.example.bro.model.Goal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionValidatorTest {
    @Test
    fun rejectsTypeWithoutTextBeforeValidatingTargetNode() = runBlocking {
        assertEquals(
            """ACTION REJECTED: TYPE requires a non-empty 'text' field (e.g., {"type":"TYPE","nodeId":1,"text":"05"}).""",
            ActionValidator.validate(
                Action(type = ActionType.TYPE, targetId = 1),
                Goal("set alarm"),
                ""
            )
        )
    }
}
