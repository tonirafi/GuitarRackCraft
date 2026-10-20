# AI Tone interchange format

Version 1. This is a separate document from Guitar RackCraft's native rack preset (`presets/*.json`).

```json
{
  "format": "guitarrackcraft-ai-tone",
  "version": 1,
  "name": "Example Rock Tone",
  "reference": {
    "song": "Example Song",
    "artist": "Example Artist",
    "style": "rock"
  },
  "description": "A suggested rock guitar tone",
  "effects": [
    {
      "pluginUri": "EXACT_INSTALLED_PLUGIN_URI",
      "enabled": true,
      "parameters": {
        "EXACT_PARAMETER_SYMBOL": 0.65
      }
    }
  ]
}
```

Parameter numbers use each port's real minimum, maximum, kind, and enumeration values. The copied prompt includes only a few plugins that match the request and whose control ports were read from the plugin. Plugins without that metadata are marked unavailable and are not given invented parameters.

## Manual paste

No API key. Copy the in-app prompt into ChatGPT, paste the JSON back, review the preview, then confirm **Pasang Nada**.

## Automatic search and generation

Search uses the public Wikipedia OpenSearch API (`https://en.wikipedia.org/w/api.php?action=opensearch`). Results are reference links, not guitar presets.

Generation stays off until a chat endpoint is saved in **Pengaturan pencarian dan AI**. The app posts a chat-completions body (`model`, `messages`) to that exact URL and sends `Authorization: Bearer` only when a key is saved on the device. The URL and key are not compiled into the app. If the endpoint is empty, generation reports that it is not configured and manual paste still works.
