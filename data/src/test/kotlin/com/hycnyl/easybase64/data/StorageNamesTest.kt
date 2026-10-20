package com.hycnyl.easybase64.data

import com.hycnyl.easybase64.core.Operation
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageNamesTest {
    @Test fun decodingNeverGuessesAnExtensionAndHandlesEmptyStem() {
        assertEquals("report.pdf", StorageNames.output("report.pdf.B64", Operation.DECODE))
        assertEquals("certificate.txt.decoded", StorageNames.output("certificate.txt", Operation.DECODE))
        assertEquals("decoded.bin", StorageNames.output(".b64", Operation.DECODE))
        assertEquals("空文件.b64", StorageNames.output("空文件", Operation.ENCODE))
    }
    @Test fun pathsAreAlwaysSingleSafeComponents() {
        assertEquals("unnamed", StorageNames.safe(".."))
        assertEquals(".._escape_thing", StorageNames.safe("../escape\\thing"))
        assertEquals("name", StorageNames.safe("na\u0000me"))
    }
    @Test fun numberingPreservesExtensionAndDoesNotReuseNames() {
        assertEquals("report (3).b64", StorageNames.unique("report.b64", setOf("report.b64", "report (1).b64", "report (2).b64")))
        assertEquals("noextension (1)", StorageNames.unique("noextension", setOf("noextension")))
    }
}
