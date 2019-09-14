//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.4-2 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2014.02.02 at 04:06:17 PM CET 
//


package com.topografix.gpx.gpx_style._0._2;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlEnumValue;
import javax.xml.bind.annotation.XmlType;


/**
 * <p>Java class for weightType.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * <p>
 * <pre>
 * &lt;simpleType name="weightType">
 *   &lt;restriction base="{http://www.w3.org/2001/XMLSchema}string">
 *     &lt;enumeration value="normal"/>
 *     &lt;enumeration value="bold"/>
 *   &lt;/restriction>
 * &lt;/simpleType>
 * </pre>
 * 
 */
@XmlType(name = "weightType")
@XmlEnum
public enum WeightType {

    @XmlEnumValue("normal")
    NORMAL("normal"),
    @XmlEnumValue("bold")
    BOLD("bold");
    private final String value;

    WeightType(String v) {
        value = v;
    }

    public String value() {
        return value;
    }

    public static WeightType fromValue(String v) {
        for (WeightType c: WeightType.values()) {
            if (c.value.equals(v)) {
                return c;
            }
        }
        throw new IllegalArgumentException(v);
    }

}