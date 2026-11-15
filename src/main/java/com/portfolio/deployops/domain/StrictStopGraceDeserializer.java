package com.portfolio.deployops.domain;

/** 소수·문자열을 정수로 조용히 변환하지 않는다. */
public final class StrictStopGraceDeserializer extends tools.jackson.databind.ValueDeserializer<Integer> {
    @Override public Integer deserialize(tools.jackson.core.JsonParser parser, tools.jackson.databind.DeserializationContext context) {
        if (!parser.hasToken(tools.jackson.core.JsonToken.VALUE_NUMBER_INT))
            return context.reportInputMismatch(Integer.class,"종료 유예 시간은 1~300초 정수로 입력해 주세요.");
        int value=parser.getIntValue();
        if(value<1 || value>300) return context.reportInputMismatch(Integer.class,"종료 유예 시간은 1~300초 정수로 입력해 주세요.");
        return value;
    }
}
