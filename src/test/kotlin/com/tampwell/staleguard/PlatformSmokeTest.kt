package com.tampwell.staleguard

import com.intellij.ide.highlighter.XmlFileType
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.PsiErrorElementUtil

/**
 * Minimal platform-fixture test kept as a canary: if the headless IDE fixture
 * itself breaks (platform bump, JDK change), this fails before anything subtle
 * does.
 */
class PlatformSmokeTest : BasePlatformTestCase() {

    fun testXmlPsiRoundTrip() {
        val psiFile = myFixture.configureByText(XmlFileType.INSTANCE, "<foo>bar</foo>")
        val xmlFile = assertInstanceOf(psiFile, XmlFile::class.java)

        assertFalse(PsiErrorElementUtil.hasErrors(project, xmlFile.virtualFile))
        assertEquals("foo", xmlFile.rootTag?.name)
        assertEquals("bar", xmlFile.rootTag?.value?.text)
    }
}
