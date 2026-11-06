package com.musicglass.desktop

import kotlin.test.*

class UpdatePayloadParserTest {
    private fun good()=SignedUpdatePayloadFixture.validJson()
    private fun parse(s: String)=SignedUpdatePayloadFixture.parse(s)
    private fun bad(s: String, code: UpdatePayloadError=UpdatePayloadError.INVALID_PAYLOAD) {
        assertEquals(UpdatePayloadParseResult.Rejected(code),parse(s))
    }
    @Test fun verifiedManifestRetainsValuesDigestAndSigner() {
        val verified=assertIs<UpdateVerificationResult.Verified>(SignedUpdatePayloadFixture.verify(good().toByteArray())).payload
        val m=assertIs<UpdatePayloadParseResult.Valid>(ValidatedUpdateManifest.parse(verified)).manifest
        assertEquals("com.musicglass.desktop",m.appId);assertEquals("preview",m.channel)
        assertEquals("0.2.0",m.version.toString());assertEquals(2L,m.releaseSequence)
        assertEquals(1793577600L,m.publishedAt);assertEquals(1794182400L,m.expiresAt)
        assertEquals("windows-x64",m.target);assertEquals(22000,m.minWindowsBuild)
        assertEquals("0.1.0",m.minInstalledVersion.toString());assertEquals(1,m.workerProtocolMajor)
        assertEquals(1,m.databaseSchemaVersion);assertEquals("Fixture notes",m.releaseNotes)
        assertEquals(123456L,m.artifact.sizeBytes);assertEquals("0".repeat(64),m.artifact.sha256)
        assertEquals("https://releases.example.invalid/a.msi?opaque=keep%20this",m.artifact.url)
        assertEquals(verified.sha256Hex(),m.payloadSha256);assertSame(verified.signer,m.signer)
    }
    @Test fun unknownMissingNullAndWrongTypesFail() {
        bad(good().replace("\"channel\":\"preview\",",""))
        bad(good().replaceFirst("{","{\"mandatory\":true,"))
        bad(good().replace("\"releaseSequence\":2","\"releaseSequence\":\"2\""))
        bad(good().replace("\"releaseSequence\":2","\"releaseSequence\":null"))
        bad(good().replace("\"releaseSequence\":2","\"releaseSequence\":true"))
        bad(good().replace("\"type\":\"msi\"","\"type\":\"zip\""))
        bad(good().replace("\"type\":\"msi\"","\"type\":\"msi\",\"args\":\"/unsafe\""))
    }
    @Test fun duplicateDecodedNamesFailAtEveryObjectLevel() {
        bad(good().replaceFirst("{","{\"schemaVersion\":1,"))
        bad(good().replaceFirst("{","{\"\\u0073chemaVersion\":1,"))
        bad(good().replace("\"type\":\"msi\"","\"type\":\"msi\",\"\\u0074ype\":\"msi\""))
    }
    @Test fun futureSchemaDispatchesBeforeV1FieldsButAfterStructure() {
        assertEquals(UpdatePayloadParseResult.Rejected(UpdatePayloadError.UNSUPPORTED_FORMAT),parse("""{"newField":[{"ok":true}],"schemaVersion":2}"""))
        bad("""{"schemaVersion":2,"schemaVersion":2}""")
        bad("""{"schemaVersion":"2"}""")
        bad("""{"schemaVersion":2,}""")
        bad("""{"schemaVersion":2,"future":[[[0]]]}""",UpdatePayloadError.SIZE_LIMIT)
    }
    @Test fun targetMismatchIsLeftForPolicyWhileInconsistencyFails() {
        assertIs<UpdatePayloadParseResult.Valid>(parse(good().replace("com.musicglass.desktop","other.product").replace("windows-x64","linux-arm64").replace("\"os\":\"windows\"","\"os\":\"linux\"").replace("\"arch\":\"x64\"","\"arch\":\"arm64\"")))
        bad(good().replace("windows-x64","windows-arm64"))
        bad(good().replace("com.musicglass.desktop","Bad Product"))
        bad(good().replace("\"channel\":\"preview\"","\"channel\":\"arbitrary\""))
    }
    @Test fun integerLexicalRulesAreStrict() {
        for(value in listOf("-1","+1","01","1.0","1e0","NaN","Infinity","false")) bad(good().replace("\"releaseSequence\":2","\"releaseSequence\":$value"))
        bad(good().replace("\"releaseSequence\":2","\"releaseSequence\":12345678901234567"),UpdatePayloadError.SIZE_LIMIT)
    }
    @Test fun numericFieldRangesAndVersionOrderingAreChecked() {
        for(pair in listOf("\"releaseSequence\":2" to "9007199254740992", "\"sizeBytes\":123456" to "2147483649",
            "\"minWindowsBuild\":22000" to "21999", "\"workerProtocolMajor\":1" to "65536",
            "\"databaseSchemaVersion\":1" to "0", "\"publishedAt\":1793577600" to "253402300800")) {
            bad(good().replace(pair.first,pair.first.substringBefore(':')+":"+pair.second))
        }
        bad(good().replace("0.1.0","0.3.0"))
        val limits=good().replace("\"releaseSequence\":2","\"releaseSequence\":9007199254740991")
            .replace("\"sizeBytes\":123456","\"sizeBytes\":2147483648")
            .replace("\"workerProtocolMajor\":1","\"workerProtocolMajor\":65535")
            .replace("\"databaseSchemaVersion\":1","\"databaseSchemaVersion\":2147483647")
        assertIs<UpdatePayloadParseResult.Valid>(parse(limits))
    }
    @Test fun timestampLifetimeIsSchemaOnlyNotCurrentClock() {
        assertIs<UpdatePayloadParseResult.Valid>(parse(good())) // fixed date may be expired; policy decides
        bad(good().replace("1794182400","1793577600"))
        bad(good().replace("1794182400","1793577599"))
        bad(good().replace("1794182400","1796169601"))
        assertIs<UpdatePayloadParseResult.Valid>(parse(good().replace("1794182400","1796169600")))
    }
    @Test fun hashesAreExactlyLowercaseHex() {
        for(hash in listOf("0".repeat(63),"0".repeat(65),"A".repeat(64),"g".repeat(64),"")) bad(good().replace("0".repeat(64),hash))
    }
    @Test fun URLSyntaxRejectsDangerousOrAmbiguousForms() {
        val original="https://releases.example.invalid/a.msi?opaque=keep%20this"
        for(url in listOf("http://releases.example.invalid/a","file:///a","https://user@releases.example.invalid/a",
            "https://releases.example.invalid/a#fragment","https://localhost/a","https://127.0.0.1/a",
            "https://[::1]/a","https://releases.example.invalid:444/a","https://releases.example.invalid:",
            "https://releases.example.invalid","https://releases.example.invalid/a/../b",
            "https://releases.example.invalid/%2e%2E/a","https://releases.example.invalid/a%2fb",
            "https://releases.example.invalid/a%5Cb","https://releases.example.invalid/%00a",
            "https://releases.example.invalid/%0Da","https://releases.example.invalid/%0aa",
            "https://releases.example.invalid/%zz","https://releases.example.invalid/a b")) bad(good().replace(original,url))
        assertIs<UpdatePayloadParseResult.Valid>(parse(good().replace(original,"https://RELEASES.example.invalid:443/a%20b?token=opaque%2Fvalue")))
    }
    @Test fun URLLengthBoundaryAndNoSensitiveDiagnosticText() {
        val prefix="https://releases.example.invalid/"
        val original="https://releases.example.invalid/a.msi?opaque=keep%20this"
        val valid=prefix+"x".repeat(2048-prefix.length)
        assertIs<UpdatePayloadParseResult.Valid>(parse(good().replace(original,valid)))
        bad(good().replace(original,valid+"x"))
        assertEquals("Rejected(code=INVALID_PAYLOAD)",parse(good().replace(original,"file:///secret?token=DO_NOT_LOG")).toString())
    }
    @Test fun validUnicodeEscapesAndNotesArePreserved() {
        val notes="संगीत الموسيقى 😀\\n\\t\\r"
        val m=assertIs<UpdatePayloadParseResult.Valid>(parse(SignedUpdatePayloadFixture.validJson(notes))).manifest
        assertEquals("संगीत الموسيقى 😀\n\t\r",m.releaseNotes)
        bad(SignedUpdatePayloadFixture.validJson("\\u0000"))
        bad(SignedUpdatePayloadFixture.validJson("\\u007f"))
        bad(SignedUpdatePayloadFixture.validJson("\\u009f"))
    }
    @Test fun signedMalformedUnicodeBOMAndTrailingDataFail() {
        assertEquals(UpdatePayloadParseResult.Rejected(UpdatePayloadError.INVALID_PAYLOAD),SignedUpdatePayloadFixture.parse(byteArrayOf(0xc3.toByte(),0x28)))
        for(s in listOf("\uFEFF"+good(),good()+"{}",good()+"garbage",SignedUpdatePayloadFixture.validJson("\\ud800"),SignedUpdatePayloadFixture.validJson("\\udc00"))) bad(s)
        assertIs<UpdatePayloadParseResult.Valid>(parse(good()+" \t\r\n"))
    }
    @Test fun scalarAndDecodedByteLimitsAreEnforced() {
        val emoji="😀"
        assertIs<UpdatePayloadParseResult.Valid>(parse(SignedUpdatePayloadFixture.validJson(emoji.repeat(4096))))
        bad(SignedUpdatePayloadFixture.validJson(emoji.repeat(4097)),UpdatePayloadError.SIZE_LIMIT)
        bad(SignedUpdatePayloadFixture.validJson("x".repeat(4097)),UpdatePayloadError.SIZE_LIMIT)
        bad(good().replaceFirst("{","{\""+"k".repeat(33)+"\":1,"),UpdatePayloadError.SIZE_LIMIT)
    }
    @Test fun rawTokenLimitCountsEscapeBytesAndQuotes() {
        val notes="\\ud83d\\ude00".repeat(2730)+"x".repeat(6) // 32766 content bytes + two quotes
        assertIs<UpdatePayloadParseResult.Valid>(parse(SignedUpdatePayloadFixture.validJson(notes)))
        bad(SignedUpdatePayloadFixture.validJson(notes+"x"),UpdatePayloadError.SIZE_LIMIT)
    }
    @Test fun bodyBoundaryIsEnforcedByAuthenticatedPipeline() {
        val exact=good()+" ".repeat(65536-good().toByteArray().size)
        assertIs<UpdatePayloadParseResult.Valid>(parse(exact))
        assertEquals(UpdateVerificationResult.Rejected(UpdateVerificationError.SIZE_LIMIT),SignedUpdatePayloadFixture.verify((exact+" ").toByteArray()))
    }
    @Test fun arraysMembersAndContainerDepthAreChecked() {
        bad(good().replace("\"artifacts\":[{","\"artifacts\":[[],{"))
        bad(good().replaceFirst("{","{\"extra\":1,"))
        bad(good().replace("\"type\":\"msi\"","\"type\":\"msi\",\"x\":{}"),UpdatePayloadError.SIZE_LIMIT)
        bad(good().replace("\"type\":\"msi\"","\"type\":\"msi\",\"extra\":1"))
        bad("[]")
    }
    @Test fun versionGrammarAndComparisonHaveNoCoercionOrOverflow() {
        for(v in listOf("v1.2.3","1.2","1.2.3.4","01.2.3","1.2.3-rc","+1.2.3","1.2.3 ","2147483648.0.0")) {
            assertNull(UpdateVersion.parse(v));bad(good().replace("0.2.0",v))
        }
        val max=UpdateVersion.parse("2147483647.2147483647.2147483647")!!
        assertTrue(max>UpdateVersion.parse("0.0.0")!!)
        assertEquals("2147483647.2147483647.2147483647",max.toString())
        assertEquals(UpdateVersion.parse("1.2.3"),UpdateVersion.parse("1.2.3"))
    }
    @Test fun genericStructureAndPropertyBoundariesApplyToFutureSchemas() {
        val key = "😀".repeat(32) // 32 scalars, 128 UTF-8 bytes
        assertEquals(UpdatePayloadParseResult.Rejected(UpdatePayloadError.UNSUPPORTED_FORMAT),parse("""{"schemaVersion":2,"$key":true}"""))
        bad("""{"schemaVersion":2,"${key}x":true}""",UpdatePayloadError.SIZE_LIMIT)
        bad("""{"schemaVersion":2,"notes":"${"x".repeat(4097)}"}""",UpdatePayloadError.SIZE_LIMIT)
        assertEquals(UpdatePayloadParseResult.Rejected(UpdatePayloadError.UNSUPPORTED_FORMAT),parse("""{"schemaVersion":9999999999999999}"""))
        bad("""{"schemaVersion":10000000000000000}""",UpdatePayloadError.SIZE_LIMIT)
        for(text in listOf("/*comment*/"+good(),good().replaceFirst("{","{//comment\n"),
            good().replace("\"schemaVersion\":1,","\"schemaVersion\":1,,"),
            good().replace("\"releaseNotes\":\"Fixture notes\"","\"releaseNotes\":\"\\q\""))) bad(text)
    }
    @Test fun artifactCardinalityAndHashTypesAreExact() {
        val artifact = good().substringAfter("\"artifacts\":[").substringBeforeLast(']')
        bad(good().replace(artifact,""))
        bad(good().replace(artifact,"$artifact,$artifact"))
        bad(good().replace("\"sha256\":\"${"0".repeat(64)}\"","\"sha256\":true"))
        bad(good().replace("\"sha256\":\"${"0".repeat(64)}\"","\"sha256\":null"))
    }
}
