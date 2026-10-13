package com.celllock.app;

import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellSignalStrengthNr;

/** 系统 CellInfo 的精简版本：只保留锁定和显示需要的字段。 */
final class RadioCell {
    final boolean nr, registered;
    final int arfcn, pci, band, rsrp, level;
    final long timestamp, identity;

    private RadioCell(boolean nr, boolean registered, int arfcn, int pci, int band, int rsrp, int level,
                      long timestamp, long identity) {
        this.nr = nr;
        this.registered = registered;
        this.arfcn = arfcn;
        this.pci = pci;
        this.band = band;
        this.rsrp = rsrp;
        this.level = level;
        this.timestamp = timestamp;
        this.identity = identity;
    }

    static RadioCell from(CellInfo info) {
        if (info instanceof CellInfoLte) {
            CellInfoLte lte = (CellInfoLte) info;
            CellIdentityLte id = lte.getCellIdentity();
            int[] bands = id.getBands();
            int band = bands.length > 0 ? bands[0] : Bands.lteBandForEarfcn(id.getEarfcn());
            return new RadioCell(false, info.isRegistered(), id.getEarfcn(), id.getPci(), band,
                    lte.getCellSignalStrength().getRsrp(), lte.getCellSignalStrength().getLevel(),
                    info.getTimestampMillis(), id.getCi());
        }
        if (info instanceof CellInfoNr) {
            CellInfoNr nr = (CellInfoNr) info;
            CellIdentityNr id = (CellIdentityNr) nr.getCellIdentity();
            CellSignalStrengthNr signal = (CellSignalStrengthNr) nr.getCellSignalStrength();
            int[] bands = id.getBands();
            int band = bands.length > 0 ? bands[0] : Bands.nrBandForArfcn(id.getNrarfcn(), null);
            return new RadioCell(true, info.isRegistered(), id.getNrarfcn(), id.getPci(), band,
                    signal.getSsRsrp(), signal.getLevel(), info.getTimestampMillis(), id.getNci());
        }
        return null;
    }

    boolean hasTarget() {
        return arfcn >= 0 && arfcn != CellInfo.UNAVAILABLE && pci >= 0 && pci != CellInfo.UNAVAILABLE;
    }

    boolean hasSignal() {
        return SignalHistory.validSignal(rsrp);
    }

    String key() {
        return (nr ? "NR" : "LTE") + ":" + arfcn + ":" + pci + ":" + identity;
    }

    String bandLabel() {
        return band <= 0 ? "—" : (nr ? "n" : "B") + band;
    }
}
