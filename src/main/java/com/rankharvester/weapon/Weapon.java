package com.rankharvester.weapon;

/** 一把武器（来自 GameData shop_weapon + shop_weapon_property 面板）。 */
public record Weapon(
        long id, String name, String codeName, int type, int subType, String typeLabel,
        String description, String specific, String tags, String icon,
        Double penetrate, Double fireRate, Double power, Double accurate,
        Double stability, Double weight, Double bullet, Double bulletMax) {

    /** 列表/搜索用精简视图。 */
    public record Brief(long id, String name, String codeName, String typeLabel) {}

    public Brief brief() {
        return new Brief(id, name, codeName, typeLabel);
    }
}
