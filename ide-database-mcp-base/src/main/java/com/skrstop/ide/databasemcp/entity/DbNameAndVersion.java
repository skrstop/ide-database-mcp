package com.skrstop.ide.databasemcp.entity;

import lombok.*;
import lombok.experimental.Accessors;

/**
 * @author 蒋时华
 * @date 2026-04-07 14:10:09
 * @since 1.0.0
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Builder
public class DbNameAndVersion {
    private String name;
    private String version;
    private String originStr;
}
