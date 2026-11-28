# Security review methodology for a soft fork

Use this model-agnostic method for human, automated, and assisted reviews. It structures evidence; it does not certify that a change is safe. State what was inspected, what was not inspected, and what observation would disprove each important conclusion. Do not include secrets, private infrastructure details, or unpublished personal data in public review notes.

## Provenance buckets

Assign each changed behavior or relevant code path one primary provenance bucket. A file may contain multiple buckets; classify the behavior, not only the filename.

| Bucket | Meaning | Review question |
| --- | --- | --- |
| `collie-original` | Introduced by Collie without a direct upstream counterpart. | What is the intended contract, and which callers and data does it control? |
| `collie-modified` | An upstream behavior changed by Collie. | Which upstream invariant changed, and what compatibility or security property depends on the difference? |
| `molly-inherited` | Behavior carried from Molly into the Collie lineage. | Is it still intended, and does its assumption hold against the current Signal base? |
| `upgrade-seam` | Adaptation, conflict resolution, or deliberate omission at a Signal or Molly upgrade boundary. | Which exact upstream revision was reconciled, and what did the merge resolution choose? |

Record commit or file evidence for the assignment. Do not infer provenance from naming alone.

## Trust domains

Trace data and control across these six domains. Mark domains not touched as not applicable with a reason.

1. **Identity and authentication:** registration, device identity, account state, linking, credentials, and key ownership.
2. **Cryptography and protocol:** key derivation, encryption, signatures, serialization, protocol versions, and cryptographic inputs.
3. **Network and remote services:** endpoints, request construction, response validation, retries, push delivery, and remote configuration.
4. **Local storage and lifecycle:** databases, files, backups, migrations, retention, restore, and process or job lifecycle.
5. **UI and user intent:** consent, settings, warnings, security labels, navigation, and whether controls accurately describe effects.
6. **Build, packaging, and platform boundary:** manifests, permissions, flavors, signing, dependencies, native code, and release artifacts.

For each relevant domain, identify inputs, authority, validation, side effects, and failure behavior. Follow cross-domain seams explicitly: for example, a UI setting that changes a network request and persists locally; a manifest permission that enables an exported receiver which updates identity state; or a migration that changes what cryptographic code reads.

## Entanglement tags

Use these tags when the evidence shows the named review risk. A tag is a prompt for investigation, not a finding by itself.

- `SECURITY-COUPLED-ENTANGLEMENT`: a product or convenience change also changes a security property, policy, or boundary.
- `DOUBLE-FORK-COLLISION`: Collie behavior overlaps with an inherited Molly change and the two lineages may diverge in meaning.
- `MONOLITH-SCATTER`: one behavior is spread across multiple edits in a recurring upstream monolith or related files.
- `UNTRACKED-REMOVAL`: a deliberate deletion affecting the merge base or upgrade behavior has no suitable entry in the removal manifest, or an entry lacks history-backed rationale.

Describe the specific code paths behind each tag and the verification that resolves or narrows the concern.

## Semantic verification

For each conclusion, list the exact evidence used: relevant source, callers, tests, resource or manifest declarations, commit range, and observed runtime behavior where available. Check both expected behavior and the failure paths. For an upgrade seam, compare the target upstream revision with the merge base and inspect the resolved result; a conflict-free merge is not proof of equivalence. Check removals for references, generated references, reflection, resources, and alternate implementations.

Prefer a focused test or reproducible runtime check tied to the claimed property. State limitations plainly: a unit test does not establish platform behavior, and static inspection does not establish runtime behavior. If evidence is unavailable, mark the claim unverified.

## Falsification criteria

Before accepting a security-relevant conclusion, state what would make it false. Examples:

- A caller or alternate code path bypasses the validation being reviewed.
- A changed default, migration, or error branch produces a different security outcome than the claimed one.
- The compared Molly and Signal revisions do not contain the behavior attributed to them.
- A listed removal is present in the selected upstream revision or remains reachable through another resource or entry point.
- A test fixture omits the state or attacker-controlled input required by the claim.
- The runtime permission, exported component, endpoint, or persisted value differs from the source-level assumption.

If a falsifier is found, update the conclusion and evidence before closing the review. If it cannot be checked, keep the conclusion qualified rather than treating absence of evidence as proof.
