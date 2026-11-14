package com.v2ray.ang.ui.base

import android.os.Bundle
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.helper.PermissionHelper

abstract class HelperBaseComponentActivity : BaseComponentActivity() {

    private lateinit var permissionRequester: PermissionHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionRequester = PermissionHelper(this)
    }

    protected fun checkAndRequestPermission(
        permissionType: PermissionType,
        onGranted: () -> Unit
    ) {
        permissionRequester.request(permissionType, onGranted)
    }
}
