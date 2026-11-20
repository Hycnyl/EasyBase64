package com.hycnyl.easybase64.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TextCodecTest {
    @Test fun preservesUnicodeWhitespaceAndLineEndings() {
        val text = " 你好\r\n🙂\t"
        val base64 = "IOS9oOWlvQ0K8J+Zggk="
        assertEquals(base64, TextCodec.transform(text, Operation.ENCODE))
        assertEquals(text, TextCodec.transform(base64, Operation.DECODE))
    }

    @Test fun distinguishesMalformedBase64FromNonTextPayloads() {
        val trailingData = assertThrows(CodecException::class.java) {
            TextCodec.transform("YQ==more", Operation.DECODE)
        }
        assertEquals("TRAILING_DATA", trailingData.code)
        val binaryPayload = assertThrows(CodecException::class.java) {
            TextCodec.transform("/w==", Operation.DECODE)
        }
        assertEquals("INVALID_UTF8", binaryPayload.code)
        assertEquals("\uFFFD", TextCodec.transform("77+9", Operation.DECODE))
    }

    @Test fun rejectsUnpairedSurrogatesWithoutReplacingThem() {
        val error = assertThrows(CodecException::class.java) {
            TextCodec.transform("\uD800", Operation.ENCODE)
        }
        assertEquals("INVALID_UNICODE", error.code)
    }
}
