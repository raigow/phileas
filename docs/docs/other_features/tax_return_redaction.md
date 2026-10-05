# Redacting a Tax Return

Phileas includes a command-line tool that removes personal identifying information from a PDF tax return, such as a
Form 1040 with its schedules and attached W-2s.

```
scripts/redact-tax-return.sh my-return.pdf my-return-redacted.pdf --name "Jordan A. Whitaker" --name "Morgan Whitaker"
```

The script builds Phileas the first time it runs (Java 21 is required). Everything runs locally and the PDF never
leaves your machine. It prints how many items of each type it redacted; add `--verbose` to list every one.

## What is redacted

| Information                                  | How it is found                                                   |
|----------------------------------------------|-------------------------------------------------------------------|
| Social security numbers and ITINs            | `ssn` filter                                                      |
| Employer identification numbers              | `ein` filter (dashed form, `12-3456789`)                          |
| Phone numbers and email addresses            | `phoneNumber` and `emailAddress` filters                          |
| Street addresses and ZIP codes               | `streetAddress` and `zipCode` filters                             |
| Dates (birth dates, signature dates)         | `date` filter                                                     |
| Bank routing numbers                         | `bankRoutingNumber` filter                                        |
| Account numbers, IP PINs, undashed SSNs/EINs | any run of 6 to 17 digits                                         |
| Preparer PTINs                               | `P` followed by 8 digits                                          |
| Names                                        | common first and last name lists, plus every `--name` you pass    |

Dollar amounts such as `84,250.00` are left readable.

## Options

| Option               | Description                                                                                         |
|----------------------|-----------------------------------------------------------------------------------------------------|
| `--name "Full Name"` | Redacts each word of the name everywhere it appears. Repeat it for yourself, your spouse, your dependents, your employer and your preparer. Any other word, such as your city, can be passed the same way. |
| `--policy file.json` | Uses your own [filter policy](../filter_policies/filter_policies.md) instead of the built-in one, which is at `src/main/resources/ai/philterd/phileas/tools/tax-return-policy.json`. |
| `--verbose`          | Lists every redacted item.                                                                          |

## Things to know

* **Pass your names with `--name`.** The common-name lists catch many first and last names but not all of them, and
  cities are not redacted unless you name them.
* **Check the output before sharing it.** Redaction is automatic and can miss things or catch extra words.
* **Scanned returns are not supported.** The tool reads the text in the PDF. A return that was scanned to images has no
  text to read and needs OCR first; the tool warns when it finds nothing to redact.
* **Fillable forms are handled.** Values typed into fillable PDF form fields are flattened into the page before
  redaction.
* **The output is images.** Each page of the redacted PDF is a picture of the original page with black boxes drawn over
  the redacted text, so nothing hidden survives, and the text can no longer be selected or searched.
