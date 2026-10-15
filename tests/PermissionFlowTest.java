package com.celllock.app;

import android.content.SharedPreferences;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Looper;
import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/** Run with app_process and the APK on CLASSPATH; never requests permissions or changes settings. */
public final class PermissionFlowTest {
    private static final class GrantedContext extends ContextWrapper {
        GrantedContext(Context base) { super(base); }
        @Override public Context getApplicationContext() { return this; }
        @Override public int checkSelfPermission(String permission) { return PackageManager.PERMISSION_GRANTED; }
        @Override public Object getSystemService(String name) {
            return Context.LOCATION_SERVICE.equals(name) ? null : super.getSystemService(name);
        }
    }
    private static void set(MainActivity activity,String name,Object value) throws Exception {
        Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);field.set(activity,value);
    }
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        System.out.println("Checking permission history on Android runtime");
        Map<String,Boolean> asked = new HashMap<>();
        SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy,method,values) -> {
                    if (!method.getName().equals("getBoolean")) throw new AssertionError("unexpected preference access");
                    return asked.getOrDefault((String)values[0],(Boolean)values[1]);
                });
        String phone="android.permission.READ_PHONE_STATE", fine="android.permission.ACCESS_FINE_LOCATION";
        asked.put("permissionsAsked",true);
        if(MainActivity.needsPermissionSettings(prefs,phone,false,false))throw new AssertionError("legacy flag blocked first phone request");
        asked.put("permissionAsked:"+phone,true);
        if(MainActivity.needsPermissionSettings(prefs,fine,false,false))throw new AssertionError("phone request blocked first fine request");
        if(!MainActivity.needsPermissionSettings(prefs,phone,false,false))throw new AssertionError("permanent phone denial not routed to settings");
        if(MainActivity.needsPermissionSettings(prefs,phone,false,true))throw new AssertionError("rationale phone request blocked");
        asked.put("permissionAsked:"+fine,true);
        if(!MainActivity.needsPermissionSettings(prefs,fine,false,false))throw new AssertionError("permanent fine denial not routed to settings");
        if(MainActivity.needsPermissionSettings(prefs,fine,false,true))throw new AssertionError("rationale fine request blocked");
        String coarse="android.permission.ACCESS_COARSE_LOCATION";
        asked.put("permissionAsked:"+coarse,true);
        if(MainActivity.needsPermissionSettings(prefs,coarse,true,false))throw new AssertionError("granted coarse permission blocked fine request");
        System.out.println("Permission history passed; checking empty-result guide");
        Class<?> threadClass=Class.forName("android.app.ActivityThread");
        Object thread=threadClass.getDeclaredMethod("systemMain").invoke(null);
        Context system=(Context)threadClass.getDeclaredMethod("getSystemContext").invoke(thread);
        MainActivity activity=new MainActivity();
        GrantedContext context=new GrantedContext(system);
        Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);
        attach.setAccessible(true);attach.invoke(activity,context);
        Field subscriptions=MainActivity.class.getDeclaredField("subscriptions");subscriptions.setAccessible(true);
        @SuppressWarnings("unchecked") List<SimInfo> sims=(List<SimInfo>)subscriptions.get(activity);
        sims.add(new SimInfo(1,0,"SIM 1","系统"));
        set(activity,"subscriptionId",1);set(activity,"selectedSlot",0);set(activity,"prefs",prefs);
        RadioReader reader=new RadioReader(context);set(activity,"reader",reader);
        Method guide=MainActivity.class.getDeclaredMethod("requestPermissionsManually",boolean.class);
        guide.setAccessible(true);
        // The permission guide must return without touching the absent CellsPage when nothing is missing.
        try { guide.invoke(activity,true); } finally { reader.close(); }
        System.out.println("PASS: 7 permission history checks and empty-result retry guard; no permission or setting changes");
        System.exit(0);
    }
}
