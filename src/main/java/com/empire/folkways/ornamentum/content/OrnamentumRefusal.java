package com.empire.folkways.ornamentum.content;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;

/** 切割机工作节点的拒绝原因（翻译键由 RefusalKind.Named 自动生成）。 */
public enum OrnamentumRefusal implements RefusalKind.Named {
    NOT_A_CUTTER,
    NOT_A_SORTER,
    STATION_BUSY,
    STATION_CHANGED,
    RECIPE_CHANGED,
    NOTHING_TO_WORK_WITH
}
