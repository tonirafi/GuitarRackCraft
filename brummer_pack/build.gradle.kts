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

plugins {
    id("com.android.asset-pack")
}

val stagePackImages = tasks.register<Copy>("stagePackImages") {
    from(rootProject.file("app/src/main/assets")) {
        include("lv2/modgui_shared_resources/**/*.png")
    }
    into(layout.projectDirectory.dir("src/main/assets"))
}

assetPack {
    packName.set("brummer_pack")
    dynamicDelivery {
        deliveryType.set("on-demand")
    }
}
