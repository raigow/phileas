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

import ai.philterd.phileas.PhileasConfiguration;
import ai.philterd.phileas.model.filtering.BinaryDocumentFilterResult;
import ai.philterd.phileas.model.filtering.MimeType;
import ai.philterd.phileas.model.filtering.Span;
import ai.philterd.phileas.policy.Policy;
import ai.philterd.phileas.policy.filters.CustomDictionary;
import ai.philterd.phileas.services.context.DefaultContextService;
import ai.philterd.phileas.services.disambiguation.vector.InMemoryVectorService;
import ai.philterd.phileas.services.filters.filtering.PdfFilterService;
import com.google.gson.Gson;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Command-line tool that removes personal identifying information from a PDF tax return.
 *
 * <p>Usage: {@code TaxReturnRedactor <input.pdf> <output.pdf> [--name "Full Name"]... [--policy policy.json] [--verbose]}</p>
 *
 * <p>The built-in policy redacts SSNs/TINs, EINs, phone numbers, email addresses, street addresses, cities, zip codes,
 * dates, routing and account numbers, PTINs, identity protection PINs, and common first and last names. Each
 * {@code --name} adds an exact term to redact, which is the most reliable way to catch the filers' and dependents'
 * names. The output PDF is rebuilt from page images, so no hidden text from the original survives.</p>
 *
 * <p>The input must contain real text. A scanned return (pages that are only images) cannot be redacted.</p>
 */
public class TaxReturnRedactor {

    static final String DEFAULT_POLICY = "/ai/philterd/phileas/tools/tax-return-policy.json";

    public static void main(final String[] args) throws Exception {

        String input = null;
        String output = null;
        String policyFile = null;
        boolean verbose = false;
        final List<String> names = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--name" -> names.addAll(splitName(requireValue(args, ++i, "--name")));
                case "--policy" -> policyFile = requireValue(args, ++i, "--policy");
                case "--verbose" -> verbose = true;
                case "-h", "--help" -> {
                    usage();
                    return;
                }
                default -> {
                    if (input == null) {
                        input = args[i];
                    } else if (output == null) {
                        output = args[i];
                    } else {
                        System.err.println("Unexpected argument: " + args[i]);
                        usage();
                        System.exit(2);
                    }
                }
            }
        }

        if (input == null || output == null) {
            usage();
            System.exit(2);
        }

        final Path inputPath = Path.of(input);
        final Path outputPath = Path.of(output);

        if (inputPath.toAbsolutePath().normalize().equals(outputPath.toAbsolutePath().normalize())) {
            System.err.println("The output file must be different from the input file.");
            System.exit(2);
        }

        final Policy policy = loadPolicy(policyFile, names);
        final BinaryDocumentFilterResult result = redact(policy, Files.readAllBytes(inputPath));

        Files.write(outputPath, result.getDocument());

        final List<Span> spans = result.getExplanation().appliedSpans();
        System.out.println("Redacted " + spans.size() + " item(s); wrote " + outputPath);
        for (final Map.Entry<String, Integer> entry : countByType(spans).entrySet()) {
            System.out.println("  " + entry.getKey() + ": " + entry.getValue());
        }

        if (verbose) {
            for (final Span span : spans) {
                System.out.println("  page " + (span.getPageNumber() + 1) + "  " + typeOf(span) + "  " + span.getText());
            }
        }

        if (spans.isEmpty()) {
            System.err.println("Warning: nothing was found to redact. If this is a scanned return, it has no text "
                    + "for this tool to read; run it through OCR first.");
        }

    }

    /**
     * Redacts a PDF using the given policy. Fillable form fields are flattened first so their values are
     * part of the page text and get redacted along with everything else.
     */
    public static BinaryDocumentFilterResult redact(final Policy policy, final byte[] pdf) throws Exception {

        final PdfFilterService pdfFilterService = new PdfFilterService(new PhileasConfiguration(new Properties()),
                new DefaultContextService(), new InMemoryVectorService(), null);

        return pdfFilterService.filter(policy, "tax-return", flattenForms(pdf), MimeType.APPLICATION_PDF);

    }

    /**
     * Loads the policy from the given file, or the built-in tax return policy when the file is null,
     * and adds a dictionary of the given names.
     */
    public static Policy loadPolicy(final String policyFile, final List<String> names) throws IOException {

        final Policy policy;

        if (policyFile != null) {
            try (Reader reader = Files.newBufferedReader(Path.of(policyFile), StandardCharsets.UTF_8)) {
                policy = new Gson().fromJson(reader, Policy.class);
            }
        } else {
            try (InputStream is = TaxReturnRedactor.class.getResourceAsStream(DEFAULT_POLICY);
                 Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                policy = new Gson().fromJson(reader, Policy.class);
            }
        }

        if (!names.isEmpty()) {
            final CustomDictionary dictionary = new CustomDictionary();
            dictionary.setClassification("name");
            dictionary.setTerms(names);
            final List<CustomDictionary> dictionaries = new ArrayList<>();
            if (policy.getIdentifiers().getCustomDictionaries() != null) {
                dictionaries.addAll(policy.getIdentifiers().getCustomDictionaries());
            }
            dictionaries.add(dictionary);
            policy.getIdentifiers().setCustomDictionaries(dictionaries);
        }

        return policy;

    }

    /** Bakes fillable form field values into the page content. Returns the input unchanged when there is no form. */
    static byte[] flattenForms(final byte[] pdf) throws IOException {

        try (PDDocument document = Loader.loadPDF(pdf)) {

            final PDAcroForm form = document.getDocumentCatalog().getAcroForm();
            if (form == null || form.getFields().isEmpty()) {
                return pdf;
            }

            form.refreshAppearances();
            form.flatten();

            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();

        }

    }

    // Each word of a name is matched on its own, so "Jordan A. Whitaker" redacts every occurrence of
    // "Jordan" and "Whitaker". Single letters and initials are skipped because they would match everywhere.
    static List<String> splitName(final String name) {
        return Arrays.stream(name.split("[\\s,]+"))
                .map(word -> word.replaceAll("\\.$", ""))
                .filter(word -> word.length() > 1)
                .toList();
    }

    private static Map<String, Integer> countByType(final List<Span> spans) {
        final Map<String, Integer> counts = new TreeMap<>();
        for (final Span span : spans) {
            counts.merge(typeOf(span), 1, Integer::sum);
        }
        return counts;
    }

    private static String typeOf(final Span span) {
        return switch (span.getFilterType().getType()) {
            case "id" -> "account-number-pin-or-ptin";
            case "custom-dictionary" -> "name-from-command-line";
            case "first-name", "surname" -> "name";
            default -> span.getFilterType().getType();
        };
    }

    private static String requireValue(final String[] args, final int i, final String option) {
        if (i >= args.length) {
            System.err.println(option + " needs a value.");
            System.exit(2);
        }
        return args[i];
    }

    private static void usage() {
        System.err.println("""
                Usage: redact-tax-return <input.pdf> <output.pdf> [options]

                Removes personal identifying information from a PDF tax return.

                Options:
                  --name "First Last"   A name to redact everywhere it appears (filer, spouse, dependents).
                                        Repeat for each person. Recommended: common-name lists miss many names.
                  --policy file.json    Use this Phileas policy instead of the built-in tax return policy.
                  --verbose             List every redacted item.
                """);
    }

}
