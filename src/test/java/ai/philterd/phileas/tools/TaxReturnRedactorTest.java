/*
 *     Copyright 2025 Philterd, LLC @ https://www.philterd.ai
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.philterd.phileas.tools;

import ai.philterd.phileas.model.filtering.BinaryDocumentFilterResult;
import ai.philterd.phileas.model.filtering.Span;
import ai.philterd.phileas.policy.Policy;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class TaxReturnRedactorTest {

    // fake-1040.pdf is an entirely fictional return. Page 1 is flattened text; page 2 holds its
    // values in fillable form fields.
    private byte[] fake1040() throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("pdfs/fake-1040.pdf")) {
            return is.readAllBytes();
        }
    }

    private Set<String> redactedText(final BinaryDocumentFilterResult result) {
        return result.getExplanation().appliedSpans().stream().map(Span::getText).collect(Collectors.toSet());
    }

    @Test
    public void redactsIdentifyingInformation() throws Exception {

        final Policy policy = TaxReturnRedactor.loadPolicy(null, List.of());
        final BinaryDocumentFilterResult result = TaxReturnRedactor.redact(policy, fake1040());
        final Set<String> redacted = redactedText(result);

        for (final String expected : List.of(
                "521-44-8873", "538-21-9046", "547-12-3398", "547-12-4410",     // SSNs
                "36-4817265", "36-1172204",                                       // EINs
                "4821 Maple Grove Lane", "62704",                                 // address
                "(217) 555-0143", "jwhitaker.fake@example.com",                   // contact
                "071000013", "4417382915",                                        // routing and account
                "482913", "P01234567",                                            // IP PIN and PTIN
                "Jordan", "Whitaker", "Morgan", "Riley", "Casey")) {              // common names
            Assertions.assertTrue(redacted.contains(expected), "Expected to redact " + expected);
        }

        // Dollar amounts and form labels stay readable.
        for (final String kept : List.of("84,250.00", "85,607.95", "84250.00", "Son", "EIN")) {
            Assertions.assertFalse(redacted.contains(kept), "Did not expect to redact " + kept);
        }

    }

    @Test
    public void redactsFillableFormFieldValues() throws Exception {

        final Policy policy = TaxReturnRedactor.loadPolicy(null, List.of());
        final BinaryDocumentFilterResult result = TaxReturnRedactor.redact(policy, fake1040());

        // The employer EIN only appears in a form field on page 2.
        Assertions.assertTrue(result.getExplanation().appliedSpans().stream()
                .anyMatch(span -> span.getText().equals("36-1172204") && span.getPageNumber() == 1));

    }

    @Test
    public void redactsGivenNames() throws Exception {

        final Policy policy = TaxReturnRedactor.loadPolicy(null, TaxReturnRedactor.splitName("Dana Okafor"));
        final Set<String> redacted = redactedText(TaxReturnRedactor.redact(policy, fake1040()));

        Assertions.assertTrue(redacted.contains("Okafor"));

    }

    @Test
    public void outputHasNoText() throws Exception {

        final Policy policy = TaxReturnRedactor.loadPolicy(null, List.of());
        final BinaryDocumentFilterResult result = TaxReturnRedactor.redact(policy, fake1040());

        try (PDDocument document = Loader.loadPDF(result.getDocument())) {
            Assertions.assertEquals(2, document.getNumberOfPages());
            Assertions.assertEquals("", new PDFTextStripper().getText(document).trim());
            Assertions.assertEquals(612, document.getPage(0).getMediaBox().getWidth(), 1);
        }

    }

    @Test
    public void splitName() {

        Assertions.assertEquals(List.of("Jordan", "Whitaker"), TaxReturnRedactor.splitName("Jordan A. Whitaker"));
        Assertions.assertEquals(List.of("Mary", "Ann", "Smith"), TaxReturnRedactor.splitName("Mary Ann  Smith"));

    }

}
