package kr.sumi.checkin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MakeUpTest {
    private fun info(json: String) = MakeUp.parseInfo(JSONObject(json))!!

    @Test fun parsesResignInfo() {
        val i = info("""{"retcode":0,"data":{"resign_cnt_daily":0,"resign_cnt_monthly":1,"resign_limit_daily":1,
            "resign_limit_monthly":3,"sign_cnt_missed":4,"quality_cnt":1,"cost":1}}""")
        assertEquals(4, i.missed)
        assertEquals(1, i.dailyLeft)
        assertEquals(2, i.monthlyLeft)
        assertTrue(i.canResign)
        assertTrue(i.hasCards)
    }

    @Test fun cannotResignWhenLimitReached() {
        val i = info("""{"retcode":0,"data":{"resign_cnt_daily":1,"resign_limit_daily":1,"resign_limit_monthly":3,"sign_cnt_missed":2}}""")
        assertFalse(i.canResign)
    }

    @Test fun notLoggedInGivesNull() {
        assertNull(MakeUp.parseInfo(JSONObject("""{"retcode":-100,"data":null}""")))
    }

    @Test fun parsesTasks() {
        val tasks = MakeUp.parseTasks(JSONObject("""{"retcode":0,"data":{"list":[
            {"id":1,"status":"TT_Ready","type":"TT_JUMP","name":"방문"},
            {"id":2,"status":"TT_Done","type":"TT_POST","name":"글"},
            {"id":3,"status":"TT_Award","type":"TT_JUMP","name":"받음"}]}}"""))
        assertEquals(listOf(true, false, false), tasks.map { it.autoCompletable })
        assertEquals(listOf(false, true, false), tasks.map { it.claimable })
    }

    @Test fun rewardShapes() {
        assertEquals("원석 x20", MakeUp.rewardOf(JSONObject("""{"data":{"award":{"name":"원석","cnt":20}}}""")))
        assertEquals("모라 x5000", MakeUp.rewardOf(JSONObject("""{"data":{"name":"모라","cnt":5000}}""")))
        assertNull(MakeUp.rewardOf(JSONObject("""{"data":{"code":"ok"}}""")))
    }
}
