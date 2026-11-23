package com.antigravity.netflixadshield.shizuku;

interface IShizukuPermissionGranter {
    void destroy() = 16777114;
    String grantPayPaySafePermissions(String packageName) = 1;
}
