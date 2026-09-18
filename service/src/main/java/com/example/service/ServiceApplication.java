package com.example.service;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.example.service.telephony.CarrierData;
import com.example.service.telephony.CarrierDataProvider;
import com.example.service.telephony.hlrlookup.HlrLookupCarrierDataProvider;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

@SpringBootApplication
public class ServiceApplication implements CommandLineRunner {

	public static void main(String[] args) {
		SpringApplication.run(ServiceApplication.class, args);
	}

	@Autowired 
	CarrierDataProvider hlrProvider;

	@Override
	public void run(String... args) throws Exception {
 		final PhoneNumberUtil PHONE_NUMBER_UTIL = PhoneNumberUtil.getInstance();

		 String no= "+91 830 8008442";
		 System.out.println(PHONE_NUMBER_UTIL.isPossibleNumber(no,null));

		 PhoneNumber ph = PHONE_NUMBER_UTIL.parse(no, null);

		 System.out.println(ph);

		 System.out.println(PHONE_NUMBER_UTIL.format(ph, PhoneNumberUtil.PhoneNumberFormat.E164));

		 System.out.println(hlrProvider.lookupCarrierData(ph, Duration.ZERO));;





	}


}
