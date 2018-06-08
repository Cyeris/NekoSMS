package com.crossbowffs.nekosms.data;

public enum SmsFilterAction {
    BLOCK,
    ALLOW;

    public static SmsFilterAction parse(String actionString) {
        if (actionString == null) {
            return null;
        }

        try {
            return SmsFilterAction.valueOf(actionString.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidFilterException("Invalid filter action value: " + actionString, e);
        }
    }
}
