#include <stdio.h>
#include <stdlib.h>	//itoa, ltoa, ultoa, fcvt, ecvt, gcvt

int main( void )
{
    int  nInt  = 1234567890;
    int  nInt2 = 255;
    long nLong = -1234567890L;
    unsigned long nULong = 345678902UL;

    float nFloat = 3.141592f;
    double nDouble = 3.14e+5;

    int radix;
    char buffer[100];
    int dec, sign;
    char *pbuffer;

    //10진 문자열로 변환
    radix = 10;
    itoa( nInt, buffer, radix );
    printf( "%s\n", buffer );

    ltoa( nLong, buffer, radix );
    printf( "%s\n", buffer );

    itoa( nULong, buffer, radix  );
    printf( "%s\n", buffer );

    //2진 문자열로 변환
    radix = 2;
    itoa( nInt2, buffer, radix );
    printf( "%s\n\n", buffer );

    //16진 문자열로 변환
    radix = 16;
    itoa( nInt2, buffer, radix );
    printf( "%s\n", buffer );

    //--- _fcvt, _ecvt, _gcvt ---
    pbuffer = fcvt( nFloat, 7, &dec, &sign );
    printf( "%s %d\n", pbuffer, dec );

    pbuffer = ecvt( nDouble, 10, &dec, &sign );
    printf( "%s %d\n", pbuffer, dec );

    pbuffer = gcvt( nDouble, 5, buffer );
    printf( "%s %d\n", pbuffer );

    pbuffer = gcvt( nDouble, 10, buffer );
    printf( "%s %d\n", pbuffer );

    return 0;
}

