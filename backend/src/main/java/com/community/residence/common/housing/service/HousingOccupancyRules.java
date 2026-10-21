package com.community.residence.common.housing.service;

/** 出租公开可用性唯一SQL规则：保留发布意愿，临时/正式占用只影响可见性。 */
public final class HousingOccupancyRules {
    private HousingOccupancyRules() { }
    public static final String FREE_HOUSE = "EXISTS (SELECT 1 FROM house hh WHERE hh.id=housing.house_id "
        + "AND hh.is_deleted=0 AND hh.status='VACANT') "
        + "AND NOT EXISTS (SELECT 1 FROM residence_application ra WHERE ra.house_id=housing.house_id AND ra.status='PENDING') "
        + "AND NOT EXISTS (SELECT 1 FROM lease_record lr WHERE lr.house_id=housing.house_id AND lr.status IN ('PENDING','TO_SIGN','ACTIVE')) "
        + "AND NOT EXISTS (SELECT 1 FROM residence_relation rr WHERE rr.house_id=housing.house_id AND rr.move_out_date IS NULL)";
    public static final String PUBLIC = "housing.status IN ('AVAILABLE','RESERVED') AND EXISTS (SELECT 1 FROM house ph WHERE ph.id=housing.house_id AND ph.is_deleted=0 AND ph.status NOT IN ('MAINTENANCE','INACTIVE')) AND "
        + "(housing.rent_type='SALE' OR (" + FREE_HOUSE + "))";
}
