// 기관 검색 화면에서 선택할 수 있는 기관 유형과 카카오 검색어를 정의
package com.my.mindot_back.centers.entity;

public enum CenterType {

    // 지역 정신건강 서비스를 제공하는 공공 정신건강복지센터
    MENTAL_HEALTH_CENTER("정신건강복지센터"),

    // 민간 또는 공공 심리상담 기관
    COUNSELING_CENTER("심리상담센터");

    private final String searchKeyword;

    CenterType(String searchKeyword) {
        this.searchKeyword = searchKeyword;
    }

    public String getSearchKeyword() {
        return searchKeyword;
    }
}