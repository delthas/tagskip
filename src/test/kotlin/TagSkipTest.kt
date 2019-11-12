package fr.delthas.tagskip.test

import fr.delthas.tagskip.TagSkipInputStream
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.slf4j.impl.SimpleLogger

class TagSkipTest {
    @Before
    fun setup() {
        System.setProperty(SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "TRACE")
    }

    @Test
    fun testTagSkip() {
        TagSkipInputStream(TagSkipTest::class.java.getResourceAsStream("/input.mp3")).buffered().use { sin ->
            TagSkipInputStream(TagSkipTest::class.java.getResourceAsStream("/skipped.mp3")).buffered().use { sinTest ->
                var i = 0
                while(true) {
                    val our = sin.read()
                    val test = sinTest.read()
                    if(our == test) {
                        if(our == -1) {
                            break
                        }
                        i++
                        continue
                    }
                    if(our == -1) {
                        Assert.fail("stripped stream shorter than test file: only $i bytes long")
                    }
                    if(test == -1) {
                        Assert.fail("stripped stream longer than test file: test file is only $i bytes long")
                    }
                    Assert.assertEquals("mismatch at offset $i", test, our)
                }
            }
        }
    }
}
