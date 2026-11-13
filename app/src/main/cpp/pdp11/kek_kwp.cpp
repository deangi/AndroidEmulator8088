#include "kek_kwp.h"
namespace kek_kwp {
namespace {
constexpr uint16_t ERR=0100000, DONE=0000200, IE=0000100, FIX=0000040, UPDN=0000020, MODE=0000010, RATE=0000006, GO=1;
constexpr uint16_t RDMASK=0100377, WRMASK=0000137;
constexpr uint32_t period[] = { 7, 70, 11667, 70000 };
bool enabled=false, irq=false; uint16_t csr=0, csb=0, cntr=0; uint64_t last=0; uint32_t remainder=0;
uint32_t steps_to_expiry(uint16_t v) { uint32_t n=(csr&UPDN)?0x10000u-v:v; return n?n:0x10000u; }
void request() { if (csr&IE) irq=true; }
void expired(uint64_t n) { if (!n) return; if ((csr&DONE)||n>1) csr|=ERR; csr|=DONE; request(); }
void advance(uint64_t n) {
 if (!(csr&GO)||!n) return; uint32_t first=steps_to_expiry(cntr);
 if (n<first) { cntr=(csr&UPDN)?uint16_t(cntr+n):uint16_t(cntr-n); return; }
 n-=first; cntr=0; if (!(csr&MODE)) { expired(1); csb=0; csr&=~GO; remainder=0; return; }
 cntr=csb; uint32_t cycle=steps_to_expiry(cntr); uint64_t count=1;
 if (n>=cycle) { count+=n/cycle; n%=cycle; } if(n) cntr=(csr&UPDN)?uint16_t(cntr+n):uint16_t(cntr-n); expired(count);
}
void update(uint64_t now) { uint64_t elapsed=last?now-last:0; last=now; if(!(csr&GO)){remainder=0;return;} uint64_t a=uint64_t(remainder)+elapsed; auto p=period[(csr&RATE)>>1]; remainder=uint32_t(a%p); advance(a/p); }
}
void set_enabled(bool value) { enabled=value; if(!enabled) reset(); }
bool contains(uint16_t a) { return enabled && a>=CSR_ADDR && a<END_ADDR; }
void reset(uint64_t now) { csr=csb=cntr=0; remainder=0; irq=false; last=now; }
void tick(uint64_t now) { if(enabled) update(now); }
bool take_interrupt() { if(!enabled||!irq) return false; irq=false; return true; }
uint16_t read_word(uint16_t a,uint64_t now) { if(!enabled)return 0; update(now); switch(a&~1u){case CSR_ADDR:{auto v=csr&RDMASK;csr&=~uint16_t(ERR|DONE);irq=false;return v;}case CNTR_ADDR:return cntr;default:return 0;} }
uint8_t read_byte(uint16_t a,uint64_t now) { auto v=read_word(a&~1u,now); return a&1?uint8_t(v>>8):uint8_t(v); }
void write_word(uint16_t a,uint16_t v,uint64_t now) { if(!enabled)return; update(now); switch(a&~1u){case CSR_ADDR:{auto old=csr; auto oldRate=old&RATE; bool running=old&GO; irq=false; csr=v&WRMASK; if(!(csr&GO)){remainder=0;if(v&FIX){cntr=(csr&UPDN)?uint16_t(cntr+1):uint16_t(cntr-1);if(!cntr)expired(1);}} else if(!running||oldRate!=(csr&RATE)){cntr=csb;remainder=0;}last=now;break;}case CSB_ADDR:csb=cntr=v;csr&=~uint16_t(ERR|DONE);irq=false;remainder=0;last=now;break;default:break;} }
void write_byte(uint16_t a,uint8_t v,uint64_t now) { uint16_t old=(a&~1u)==CSR_ADDR?csr:(a&~1u)==CSB_ADDR?csb:cntr; old=(a&1)?uint16_t((old&0377)|(uint16_t(v)<<8)):uint16_t((old&0177400)|v); write_word(a&~1u,old,now); }
}
