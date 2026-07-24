// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.cef.callback.CefContextMenuParams;
import org.cef.callback.CefMenuModel;
import org.cef.callback.CefMenuModel.MenuColorType;
import org.cef.misc.IntRef;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

class CefMenuModelApiTest {
    @Test
    void menuIdsMatchCef151() {
        assertEquals(115, CefMenuModel.MenuId.MENU_ID_PASTE_MATCH_STYLE);
        assertEquals(116, CefMenuModel.MenuId.MENU_ID_DELETE);
        assertEquals(117, CefMenuModel.MenuId.MENU_ID_SELECT_ALL);
        assertEquals(206, CefMenuModel.MenuId.MENU_ID_ADD_TO_DICTIONARY);
        assertEquals(220, CefMenuModel.MenuId.MENU_ID_CUSTOM_FIRST);
        assertEquals(250, CefMenuModel.MenuId.MENU_ID_CUSTOM_LAST);
        assertEquals(26500, CefMenuModel.MenuId.MENU_ID_USER_FIRST);
        assertEquals(28500, CefMenuModel.MenuId.MENU_ID_USER_LAST);
    }

    @Test
    void menuColorTypesMatchCef151() {
        int[] values = Arrays.stream(MenuColorType.values()).mapToInt(MenuColorType::getValue).toArray();
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5}, values);
    }

    @Test
    void exposesBoundedMenuAndContextParameterAdditions() throws Exception {
        assertEquals(boolean.class, CefMenuModel.class.getMethod("isSubMenu").getReturnType());
        assertEquals(boolean.class, CefMenuModel.class.getMethod("setColor", int.class, MenuColorType.class, int.class).getReturnType());
        assertEquals(boolean.class, CefMenuModel.class.getMethod("setColorAt", int.class, MenuColorType.class, int.class).getReturnType());
        assertEquals(boolean.class, CefMenuModel.class.getMethod("getColor", int.class, MenuColorType.class, IntRef.class).getReturnType());
        assertEquals(boolean.class, CefMenuModel.class.getMethod("getColorAt", int.class, MenuColorType.class, IntRef.class).getReturnType());
        assertEquals(boolean.class, CefMenuModel.class.getMethod("setFontList", int.class, String.class).getReturnType());
        assertEquals(boolean.class, CefMenuModel.class.getMethod("setFontListAt", int.class, String.class).getReturnType());
        assertEquals(String.class, CefContextMenuParams.class.getMethod("getTitleText").getReturnType());
        assertEquals(boolean.class, CefContextMenuParams.class.getMethod("isCustomMenu").getReturnType());
    }

    @Test
    void adjacentContextMenuEnumsMatchCef151() {
        assertEquals(4, CefContextMenuParams.MediaType.CM_MEDIATYPE_CANVAS.ordinal());
        assertEquals(5, CefContextMenuParams.MediaType.CM_MEDIATYPE_FILE.ordinal());
        assertEquals(6, CefContextMenuParams.MediaType.CM_MEDIATYPE_PLUGIN.ordinal());
        assertEquals(1 << 0, CefContextMenuParams.MediaStateFlags.CM_MEDIAFLAG_IN_ERROR);
        assertEquals(1 << 6, CefContextMenuParams.MediaStateFlags.CM_MEDIAFLAG_CAN_TOGGLE_CONTROLS);
        assertEquals(1 << 7, CefContextMenuParams.MediaStateFlags.CM_MEDIAFLAG_CONTROLS);
        assertEquals(1 << 10, CefContextMenuParams.MediaStateFlags.CM_MEDIAFLAG_CAN_PICTURE_IN_PICTURE);
        assertEquals(1 << 11, CefContextMenuParams.MediaStateFlags.CM_MEDIAFLAG_PICTURE_IN_PICTURE);
        assertEquals(1 << 12, CefContextMenuParams.MediaStateFlags.CM_MEDIAFLAG_CAN_LOOP);
        assertEquals(1 << 8, CefContextMenuParams.EditStateFlags.CM_EDITFLAG_CAN_EDIT_RICHLY);
        assertEquals(CefContextMenuParams.MediaStateFlags.CM_MEDIAFLAG_IN_ERROR, CefContextMenuParams.MediaStateFlags.CM_MEDIAFLAG_ERROR);
        assertEquals(CefContextMenuParams.MediaStateFlags.CM_MEDIAFLAG_CAN_TOGGLE_CONTROLS, CefContextMenuParams.MediaStateFlags.CM_MEDIAFLAG_HAS_VIDEO);
        assertEquals(CefContextMenuParams.MediaStateFlags.CM_MEDIAFLAG_CONTROLS, CefContextMenuParams.MediaStateFlags.CM_MEDIAFLAG_CONTROL_ROOT_ELEMENT);
    }
}
