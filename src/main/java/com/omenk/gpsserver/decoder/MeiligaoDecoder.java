/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.omenk.gpsserver.decoder;

import com.omenk.gpsserver.helper.Crc;
import com.omenk.gpsserver.model.Position;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.HeapChannelBufferFactory;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.handler.codec.oneone.OneToOneDecoder;

/**
 *
 * @author omenkzz
 */
public class MeiligaoDecoder extends OneToOneDecoder{
    
    
    static private Pattern pattern = Pattern.compile(
            "([\\d]{2})([\\d]{2})([\\d]{2}).([\\d]{3})," + // Time (HHMMSS.SSS)
            "([AV])," +                         // Validity
            "([\\d]{2})([\\d]{2}.[\\d]{4})," +  // Latitude (DDMM.MMMM)
            "([NS])," +
            "([\\d]{3})([\\d]{2}.[\\d]{4})," +  // Longitude (DDDMM.MMMM)
            "([EW])," +
            "([\\d]+.[\\d]+)," +                // Speed
            "([\\d]+.[\\d]+)?," +               // Course
            "([\\d]{2})([\\d]{2})([\\d]{2})," + // Date (DDMMYY)
            "[^\\|]+\\|(\\d+.\\d)\\|" +         // Dilution of precision
            "(\\d+)\\|" +                       // Altitude
            "([0-9a-fA-F]+)\\|" +               // State
            ".*"); // TODO: parse ADC

    /**
     * Parse device id
     */
    private String getId(ChannelBuffer buf) {
        String id = "";

        for (int i = 0; i < 7; i++) {
            int b = buf.getUnsignedByte(i);

            // First digit
            int d1 = (b & 0xf0) >> 4;
            if (d1 == 0xf) break;
            id += d1;

            // Second digit
            int d2 = (b & 0x0f);
            if (d2 == 0xf) break;
            id += d2;
        }

        return id;
    }

    /**
     * Decode message
     */
    protected Object decode(
            ChannelHandlerContext ctx, Channel channel, Object msg)
            throws Exception {

        ChannelBuffer buf = (ChannelBuffer) msg;
        int command = buf.getUnsignedShort(7);

        // Login confirmation
        if (command == 0x5000) {
            ChannelBuffer sendBuf = HeapChannelBufferFactory.getInstance().getBuffer(18);
            sendBuf.writeByte('@');
            sendBuf.writeByte('@');
            sendBuf.writeShort(sendBuf.capacity());
            byte[] array = new byte[7];
            buf.getBytes(0, array);
            sendBuf.writeBytes(array);
            sendBuf.writeShort(0x4000);
            sendBuf.writeByte(0x01);
            array = new byte[sendBuf.readableBytes()];
            sendBuf.getBytes(0, array);
            sendBuf.writeShort(Crc.crc16Ccitt(array));
            sendBuf.writeByte('\r');
            sendBuf.writeByte('\n');
            if (channel != null) {
                channel.write(sendBuf);
            }
        }

        // Data offset
        int offset = 7 + 2;
        if (command == 0x9955) {
            offset += 0;
        } else if (command == 0x9016) {
            offset += 6;
        } else if (command == 0x9999) {
            offset += 1;
        } else {
            return null;
        }

        // Create new position
        Position position = new Position();
        String extendedInfo = "<protocol>meiligao</protocol>";

        // Get device by id
        // TODO: change imei to unique id
        position.setDeviceId(Long.MIN_VALUE);

        // Parse message
        String sentence = buf.toString(offset, buf.readableBytes() - offset - 4, Charset.defaultCharset());
        Matcher parser = pattern.matcher(sentence);
        if (!parser.matches()) {
            throw new ParseException(null, 0);
        }

        Integer index = 1;

        // Time
        Calendar time = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        time.clear();
        time.set(Calendar.HOUR, Integer.valueOf(parser.group(index++)));
        time.set(Calendar.MINUTE, Integer.valueOf(parser.group(index++)));
        time.set(Calendar.SECOND, Integer.valueOf(parser.group(index++)));
        time.set(Calendar.MILLISECOND, Integer.valueOf(parser.group(index++)));

        // Validity
        position.setValid(parser.group(index++).compareTo("A") == 0 ? true : false);

        // Latitude
        Double latitude = Double.valueOf(parser.group(index++));
        latitude += Double.valueOf(parser.group(index++)) / 60;
        if (parser.group(index++).compareTo("S") == 0) latitude = -latitude;
        position.setLatitude(latitude);

        // Longitude
        Double lonlitude = Double.valueOf(parser.group(index++));
        lonlitude += Double.valueOf(parser.group(index++)) / 60;
        if (parser.group(index++).compareTo("W") == 0) lonlitude = -lonlitude;
        position.setLongitude(lonlitude);

        // Speed
        position.setSpeed(Double.valueOf(parser.group(index++)));

        // Course
        String course = parser.group(index++);
        if (course != null) {
            position.setCourse(Double.valueOf(course));
        } else {
            position.setCourse(0.0);
        }

        // Date
        time.set(Calendar.DAY_OF_MONTH, Integer.valueOf(parser.group(index++)));
        time.set(Calendar.MONTH, Integer.valueOf(parser.group(index++)) - 1);
        time.set(Calendar.YEAR, 2000 + Integer.valueOf(parser.group(index++)));
        position.setTime(time.getTime());

        // Dilution of precision
        extendedInfo += "<hdop>" + parser.group(index++) + "</hdop>";

        // Altitude
        String altitude = parser.group(index++);
        if (altitude != null) {
            position.setAltitude(Double.valueOf(altitude));
        } else {
            position.setAltitude(0.0);
        }

        // State
        extendedInfo += "<state>" + parser.group(index++) + "</state>";

        // Extended info
        position.setExtendedInfo(extendedInfo);

        return position;
    }

    
    
}
