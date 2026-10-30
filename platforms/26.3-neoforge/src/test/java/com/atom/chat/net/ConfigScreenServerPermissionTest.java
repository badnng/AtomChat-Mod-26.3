package com.atom.chat.net;

import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigScreenServerPermissionTest {
    @Test
    void remotePlayersBelowGamemasterCannotEdit() {
        assertFalse(ConfigScreenServer.mayEdit(false, false, PermissionSet.NO_PERMISSIONS));
        assertFalse(ConfigScreenServer.mayEdit(false, false, LevelBasedPermissionSet.MODERATOR));
    }

    @Test
    void gamemastersAndHigherMayEdit() {
        assertTrue(ConfigScreenServer.mayEdit(false, false, LevelBasedPermissionSet.GAMEMASTER));
        assertTrue(ConfigScreenServer.mayEdit(false, false, LevelBasedPermissionSet.ADMIN));
        assertTrue(ConfigScreenServer.mayEdit(false, false, LevelBasedPermissionSet.OWNER));
    }

    @Test
    void singleplayerHostDoesNotNeedCheats() {
        assertTrue(ConfigScreenServer.mayEdit(true, true, PermissionSet.NO_PERMISSIONS));
    }

    @Test
    void lanGuestCannotUseTheHostsNoCheatsExemption() {
        assertFalse(ConfigScreenServer.mayEdit(true, false, PermissionSet.NO_PERMISSIONS));
        assertFalse(ConfigScreenServer.mayEdit(true, false, LevelBasedPermissionSet.MODERATOR));
        assertTrue(ConfigScreenServer.mayEdit(true, false, LevelBasedPermissionSet.GAMEMASTER));
    }

    @Test
    void dedicatedServerNeverGetsTheSingleplayerExemption() {
        assertFalse(ConfigScreenServer.mayEdit(false, true, PermissionSet.NO_PERMISSIONS));
    }

    @Test
    void revokingPermissionsDeniesTheNextRequest() {
        AtomicBoolean allowed = new AtomicBoolean(true);
        PermissionSet permissions = permission -> allowed.get();
        assertTrue(ConfigScreenServer.mayEdit(false, false, permissions));
        allowed.set(false);
        assertFalse(ConfigScreenServer.mayEdit(false, false, permissions));
    }
}
