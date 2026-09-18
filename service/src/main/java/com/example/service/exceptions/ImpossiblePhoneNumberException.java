package com.example.service.exceptions;

public class ImpossiblePhoneNumberException extends Exception{

    public ImpossiblePhoneNumberException(){
        super();
    }

    public ImpossiblePhoneNumberException(Throwable throwable){
        super(throwable);
    }
    
}
