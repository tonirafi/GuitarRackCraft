/*
 * Copyright (C) 2026 Kamil Lulko <kamil.lulko@gmail.com>
 *
 * This file is part of Guitar RackCraft.
 *
 * Guitar RackCraft is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Guitar RackCraft is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Guitar RackCraft. If not, see <https://www.gnu.org/licenses/>.
 */

#include "LV2Utils.h"
#include "../IPlugin.h"
#include <android/log.h>
#include <cstring>
#include <fstream>
#include <sstream>
#include <unistd.h>

#define LOG_TAG "LV2Utils"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace guitarrackcraft {

#if defined(HAVE_LV2) && HAVE_LV2 == 1

std::string resolveX11UIBinaryPath(const LilvUI* ui, const LilvPlugin* plugin, LilvWorld* world) {
    const LilvNode* binaryUri = lilv_ui_get_binary_uri(ui);
    if (!binaryUri) return {};

    const char* uriStr = lilv_node_as_string(binaryUri);
    char* parsedPath = lilv_file_uri_parse(uriStr, nullptr);
    std::string result;

    if (parsedPath && access(parsedPath, F_OK) == 0) {
        result = parsedPath;
    } else {
        if (parsedPath) lilv_free(parsedPath);
        parsedPath = nullptr;

        // Try bundle root: use UI bundle URI first, then plugin bundle URI
        const LilvNode* bundleUriNode = lilv_ui_get_bundle_uri(ui);
        if (!bundleUriNode) {
            bundleUriNode = lilv_plugin_get_bundle_uri(plugin);
        }
        if (bundleUriNode) {
            char* bundlePathStr = lilv_file_uri_parse(lilv_node_as_string(bundleUriNode), nullptr);
            if (bundlePathStr) {
                std::string bundleStr(bundlePathStr);
                lilv_free(bundlePathStr);
                if (!bundleStr.empty() && bundleStr.back() == '/') bundleStr.pop_back();
                const char* lastSlash = strrchr(uriStr, '/');
                const char* filename = lastSlash ? (lastSlash + 1) : uriStr;
                std::string fallback = bundleStr + "/" + filename;
                if (access(fallback.c_str(), F_OK) == 0) {
                    result = fallback;
                }
            }
        }
    }
    if (parsedPath) lilv_free(parsedPath);
    return result;
}

bool discoverModguiMetadata(const LilvPlugin* plugin, PluginInfo& info) {
    const LilvNode* bundleUriNode = lilv_plugin_get_bundle_uri(plugin);
    if (!bundleUriNode) return false;
    const char* bundleUri = lilv_node_as_string(bundleUriNode);
    if (!bundleUri) return false;
    char* bundlePath = lilv_file_uri_parse(bundleUri, nullptr);
    if (!bundlePath) return false;

    std::string bundleStr(bundlePath);
    std::string modguiTtlPath = bundleStr + "/modgui.ttl";
    std::ifstream f(modguiTtlPath);
    if (!f.good()) {
        // Some bundles use "modguis.ttl" (e.g. gx_redeye, gx_vibe, gxautowah)
        modguiTtlPath = bundleStr + "/modguis.ttl";
        f.open(modguiTtlPath);
        if (!f.good()) {
            lilv_free(bundlePath);
            return false;
        }
    }
    std::stringstream buf;
    buf << f.rdbuf();
    std::string content = buf.str();
    f.close();

    size_t idPos = content.find(info.id);
    if (content.empty() || idPos == std::string::npos) {
        lilv_free(bundlePath);
        return false;
    }

    // Search for iconTemplate after this plugin's URI (not from start of file)
    const std::string key = "modgui:iconTemplate";
    size_t keyPos = content.find(key, idPos);
    if (keyPos == std::string::npos) {
        lilv_free(bundlePath);
        return false;
    }
    size_t openAngle = content.find('<', keyPos);
    if (openAngle == std::string::npos) {
        lilv_free(bundlePath);
        return false;
    }
    size_t closeAngle = content.find('>', openAngle);
    if (closeAngle == std::string::npos) {
        lilv_free(bundlePath);
        return false;
    }
    info.modguiIconTemplate = content.substr(openAngle + 1, closeAngle - openAngle - 1);
    if (info.modguiIconTemplate.empty()) {
        lilv_free(bundlePath);
        return false;
    }
    info.modguiBasePath = bundlePath;
    lilv_free(bundlePath);
    return true;
}

namespace {

std::string unitLabel(const LilvNode* node) {
    if (!node) return {};
    const char* text = lilv_node_as_string(node);
    if (!text || text[0] == '\0') return {};
    std::string value(text);
    const auto hash = value.rfind('#');
    const auto slash = value.rfind('/');
    size_t cut = std::string::npos;
    if (hash != std::string::npos) cut = hash;
    else if (slash != std::string::npos && value.find("://") != std::string::npos) cut = slash;
    if (cut != std::string::npos && cut + 1 < value.size()) return value.substr(cut + 1);
    return value;
}

} // namespace

void readLv2PortMetadata(LilvWorld* world, const LilvPlugin* plugin, std::vector<PortInfo>& ports) {
    if (!world || !plugin) return;
    uint32_t numPorts = lilv_plugin_get_num_ports(plugin);
    ports.clear();
    ports.reserve(numPorts);

    LilvNode* audioClass = lilv_new_uri(world, LILV_URI_AUDIO_PORT);
    LilvNode* controlClass = lilv_new_uri(world, LILV_URI_CONTROL_PORT);
    LilvNode* inputClass = lilv_new_uri(world, LILV_URI_INPUT_PORT);
    LilvNode* toggledProp = lilv_new_uri(world, "http://lv2plug.in/ns/lv2core#toggled");
    LilvNode* integerProp = lilv_new_uri(world, "http://lv2plug.in/ns/lv2core#integer");
    LilvNode* unitPred = lilv_new_uri(world, "http://lv2plug.in/ns/extensions/units#unit");

    for (uint32_t i = 0; i < numPorts; ++i) {
        const LilvPort* port = lilv_plugin_get_port_by_index(plugin, i);
        if (!port) continue;

        PortInfo portInfo;
        portInfo.index = i;
        const LilvNode* portSymbol = lilv_port_get_symbol(plugin, port);
        LilvNode* portName = lilv_port_get_name(plugin, port);
        if (portName) {
            const char* text = lilv_node_as_string(portName);
            if (text) portInfo.name = text;
            lilv_node_free(portName);
        }
        if (portSymbol) {
            const char* text = lilv_node_as_string(portSymbol);
            if (text) portInfo.symbol = text;
        }

        const bool isAudio = lilv_port_is_a(plugin, port, audioClass);
        const bool isControl = lilv_port_is_a(plugin, port, controlClass);
        portInfo.isAudio = isAudio;
        portInfo.isControl = isControl;
        portInfo.isInput = lilv_port_is_a(plugin, port, inputClass);
        portInfo.isToggle = isControl && lilv_port_has_property(plugin, port, toggledProp);
        portInfo.isInteger = isControl && lilv_port_has_property(plugin, port, integerProp);

        if (isControl) {
            LilvNode* defNode = nullptr;
            LilvNode* minNode = nullptr;
            LilvNode* maxNode = nullptr;
            lilv_port_get_range(plugin, port, &defNode, &minNode, &maxNode);
            if (defNode) {
                portInfo.defaultValue = static_cast<float>(lilv_node_as_float(defNode));
                lilv_node_free(defNode);
            }
            if (minNode) {
                portInfo.minValue = static_cast<float>(lilv_node_as_float(minNode));
                lilv_node_free(minNode);
            }
            if (maxNode) {
                portInfo.maxValue = static_cast<float>(lilv_node_as_float(maxNode));
                lilv_node_free(maxNode);
            }
            LilvScalePoints* points = lilv_port_get_scale_points(plugin, port);
            if (points) {
                LILV_FOREACH(scale_points, it, points) {
                    const LilvScalePoint* sp = lilv_scale_points_get(points, it);
                    if (!sp) continue;
                    ScalePoint spOut;
                    const LilvNode* labelNode = lilv_scale_point_get_label(sp);
                    const LilvNode* valueNode = lilv_scale_point_get_value(sp);
                    if (labelNode) spOut.label = lilv_node_as_string(labelNode);
                    if (valueNode) spOut.value = static_cast<float>(lilv_node_as_float(valueNode));
                    portInfo.scalePoints.push_back(spOut);
                }
                lilv_scale_points_free(points);
            }
            LilvNode* unitNode = lilv_port_get(plugin, port, unitPred);
            if (unitNode) {
                portInfo.unit = unitLabel(unitNode);
                lilv_node_free(unitNode);
            }
        }
        ports.push_back(portInfo);
    }

    lilv_node_free(audioClass);
    lilv_node_free(controlClass);
    lilv_node_free(inputClass);
    lilv_node_free(toggledProp);
    lilv_node_free(integerProp);
    lilv_node_free(unitPred);
}

#endif

} // namespace guitarrackcraft
