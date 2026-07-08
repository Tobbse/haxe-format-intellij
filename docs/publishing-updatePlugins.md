# Publishing updatePlugins.xml

> **Status: this repo now self-publishes.** The `publish-update-xml` job in
> `.github/workflows/build.yml` deploys
> `https://tobbse.github.io/haxe-format-intellij/updatePlugins.xml` on every release
> tag (one-time repo setting: Settings → Pages → Source: "GitHub Actions"). Developers
> add that URL once under Settings → Plugins → ⚙ → Manage Plugin Repositories (team
> setup automation may seed it). The rest of this document is the **optional
> alternative**: serving both plugins from the intellij-haxe fork's single URL.

---

The team's original custom plugin repository URL is
`https://carostobbe.github.io/intellij-haxe/updatePlugins.xml`. That file is **not
stored in git** — the fork's `release.yml` workflow generates it from a heredoc and
deploys it to GitHub Pages via `actions/upload-pages-artifact` + `actions/deploy-pages`
on every fork release tag.

To serve **both** plugins from that one URL without either entry going stale, move the
XML generation into a standalone, re-triggerable workflow in the fork repo that reads
the **latest release of both repos** from the GitHub API at deploy time.

## Prerequisite

`haxe-format-intellij` must be pushed to GitHub **public** (like the fork): the XML and
the release-asset zip URLs must be downloadable by every developer's IDE without
authentication. If it must stay private, the fork workflow additionally needs a PAT to
read our releases — and IDEs still couldn't download the zip, so public is effectively
required.

## Step 1 — new file in carostobbe/intellij-haxe: `.github/workflows/pages.yml`

```yaml
name: Publish updatePlugins.xml

on:
  workflow_dispatch:                     # manual re-run from the Actions tab / gh CLI
  workflow_call:                         # invoked by release.yml after a fork release
  repository_dispatch:
    types: [update-plugins-xml]          # optional: fired by haxe-format-intellij releases

permissions:
  contents: read
  pages: write
  id-token: write

concurrency:
  group: pages
  cancel-in-progress: false

jobs:
  publish:
    runs-on: ubuntu-latest
    environment:
      name: github-pages
      url: ${{ steps.deploy.outputs.page_url }}
    env:
      GH_TOKEN: ${{ github.token }}
      FORMATTER_REPO: Tobbse/haxe-format-intellij
    steps:
      - name: Fetch latest releases of both plugins
        id: rel
        run: |
          set -euo pipefail
          fork=$(gh api "repos/${GITHUB_REPOSITORY}/releases/latest")
          {
            echo "fork_version=$(jq -r '.tag_name | ltrimstr("v")' <<<"$fork")"
            echo "fork_url=$(jq -r '[.assets[] | select(.name | endswith(".zip"))][0].browser_download_url' <<<"$fork")"
          } >> "$GITHUB_OUTPUT"
          # Formatter plugin entry is optional: omitted until its first release exists.
          if fmt=$(gh api "repos/${FORMATTER_REPO}/releases/latest" 2>/dev/null); then
            {
              echo "fmt_version=$(jq -r '.tag_name | ltrimstr("v")' <<<"$fmt")"
              echo "fmt_url=$(jq -r '[.assets[] | select(.name | endswith(".zip"))][0].browser_download_url' <<<"$fmt")"
            } >> "$GITHUB_OUTPUT"
          else
            echo "fmt_version=" >> "$GITHUB_OUTPUT"
          fi

      - name: Generate updatePlugins.xml
        run: |
          set -euo pipefail
          mkdir -p public
          {
            echo '<?xml version="1.0" encoding="UTF-8"?>'
            echo '<plugins>'
            cat <<EOF
            <plugin
              id="com.intellij.plugins.haxe"
              url="${{ steps.rel.outputs.fork_url }}"
              version="${{ steps.rel.outputs.fork_version }}">
              <!-- keep in sync with the fork's plugin.xml -->
              <idea-version since-build="253" until-build="261.*"/>
              <name>Haxe Toolkit Support (Fork)</name>
              <vendor>${GITHUB_REPOSITORY_OWNER}</vendor>
              <description><![CDATA[Custom fork of the Haxe IntelliJ plugin.]]></description>
            </plugin>
          EOF
            if [ -n "${{ steps.rel.outputs.fmt_version }}" ]; then
              cat <<EOF
            <plugin
              id="com.innogames.haxeformatter"
              url="${{ steps.rel.outputs.fmt_url }}"
              version="${{ steps.rel.outputs.fmt_version }}">
              <!-- keep in sync with haxe-format-intellij's plugin.xml -->
              <idea-version since-build="253" until-build="261.*"/>
              <name>Haxe Formatter (hxformat.json)</name>
              <vendor>InnoGames</vendor>
              <description><![CDATA[Reformat Code for .hx via haxe-formatter/hxformat.json.]]></description>
            </plugin>
          EOF
            fi
            echo '</plugins>'
          } > public/updatePlugins.xml
          cat public/updatePlugins.xml

      - name: Upload Pages artifact
        uses: actions/upload-pages-artifact@v3
        with:
          path: public

      - name: Deploy to GitHub Pages
        id: deploy
        uses: actions/deploy-pages@v4
```

Note the deliberate trade-off: `since-build`/`until-build` are **hardcoded with a
"keep in sync" comment** (they change rarely — when the IDE range is bumped, you are
editing these repos anyway). The fork's previous workflow extracted them from the
built plugin.xml; preserving that would require passing them through `workflow_call`
inputs and a fallback for dispatch triggers — more YAML than the sync burden is worth.
The values in the XML are only a pre-filter; the zip's own plugin.xml stays
authoritative in the IDE.

## Step 2 — edit `carostobbe/intellij-haxe`'s `.github/workflows/release.yml`

Remove from the `release` job:
- the `Generate updatePlugins.xml`, `Upload Pages artifact`, and `Deploy to GitHub Pages` steps,
- the `environment:` block,
- `pages: write` and `id-token: write` from `permissions:` (keep `contents: write`),
- the `Extract IDE compatibility…` step (only fed the XML generation).

Append a second job that chains the new workflow after each fork release:

```yaml
  publish-update-xml:
    needs: release
    uses: ./.github/workflows/pages.yml
    permissions:
      contents: read
      pages: write
      id-token: write
```

(Job chaining via `workflow_call` sidesteps the GitHub rule that events created with
the default `GITHUB_TOKEN` — like the release itself — don't trigger other workflows.)

## Step 3 — refresh the XML when *this* plugin releases

Baseline (zero secrets): after tagging a `haxe-format-intellij` release, anyone with
write access runs

```sh
gh workflow run pages.yml -R carostobbe/intellij-haxe
```

Optional full automation: add to this repo's release workflow, after the
`gh release create` step:

```yaml
      - name: Refresh shared updatePlugins.xml
        env:
          GH_TOKEN: ${{ secrets.INTELLIJ_HAXE_DISPATCH_TOKEN }}
        run: >
          gh api repos/carostobbe/intellij-haxe/dispatches
          -f event_type=update-plugins-xml
```

where `INTELLIJ_HAXE_DISPATCH_TOKEN` is a fine-grained PAT (owner: whoever has write
access; repository: `carostobbe/intellij-haxe`; permission: Contents → Read and write)
stored as an Actions secret in this repo.

## Rollout order

1. Push this repo to GitHub (public) and tag `v0.1.0` → release zip exists.
2. Add `pages.yml` + the `release.yml` edit in carostobbe/intellij-haxe (direct push —
   write access suffices; repo Settings are not needed, Pages is already enabled).
3. Trigger once: `gh workflow run pages.yml -R carostobbe/intellij-haxe`.
4. Verify `https://carostobbe.github.io/intellij-haxe/updatePlugins.xml` lists both
   plugins; in IntelliJ, Settings → Plugins should offer "Haxe Formatter
   (hxformat.json)" with no new repository URL needed.
5. (Optional) add the dispatch step + PAT for hands-off refreshes.
```
