package com.makefunstuff.herdr.push

import com.makefunstuff.herdr.core.push.Watch
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/** Push settings saved by an older app still load. */
class PushStoreTest {
    @Test fun aTaskWatchFromBeforeIsDroppedAndTheRestKept() {
        val file = Files.createTempFile("push", ".json").toFile()
        file.writeText(
            """{"on":true,"phoneId":"phone-1","watches":[
              {"machineId":"m1","what":"ChecksPass","taskId":"api/3","name":"API-3"},
              {"machineId":"m1","what":"AgentDone","paneId":"w1:p1","name":"api"}]}""",
        )
        val s = PushStore(file).state.value
        assertEquals(true, s.on)
        assertEquals("phone-1", s.phoneId)
        assertEquals(listOf(Watch("m1", Watch.What.AgentDone, paneId = "w1:p1", name = "api")), s.watches)
    }
}
