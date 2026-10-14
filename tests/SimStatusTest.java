package com.celllock.app;

/** 用 JDK 运行，覆盖已授权但系统返回空订阅列表的场景。 */
public final class SimStatusTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        SimStatus blankPermission = SimStatus.resolve(true, false, true, false);
        check(blankPermission == SimStatus.RESTRICTED, "已授权且 SIM 就绪，空列表不能显示没有 SIM");
        check(!blankPermission.emptyLabel.equals(SimStatus.NONE.emptyLabel), "受限与无卡需要不同提示");
        check(blankPermission.permissionLabel.equals("系统限制"), "设备页不能把受限权限显示为已授予");
        check(SimStatus.resolve(true, false, false, true) == SimStatus.RESTRICTED, "权限异常不能静默显示无卡");
        check(SimStatus.resolve(false, false, true, false) == SimStatus.PERMISSION_REQUIRED, "未授权时先引导授权");
        check(SimStatus.resolve(true, false, false, false) == SimStatus.NONE, "确实无卡时保留无卡提示");
        check(SimStatus.resolve(true, true, true, false) == SimStatus.AVAILABLE, "真实订阅可用时正常选择 SIM");
        System.out.println("SimStatus: " + checks + " checks passed");
    }
}
