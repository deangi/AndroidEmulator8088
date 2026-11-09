ReadMe.txt
Android version, Nov 8, 2026
Dean Gienger


CP/M 2.2 runs
Boot with CDRIVE.HDD - 8MB HDD
User 0 - base, MBASIC, some basic programs
User 1 - WordStar
User 2 - SuperCalc
User 3 - SimpleC2.7 - not working
User 4 - Fortran - working
User 5 - SuperSoft C 1125 - working

To move pip to some user:
a:
user 0
pip
^C
c:
user 5
save 29 pip.com

To copy from some user (0 in this example) to current
pip c:=d:*.*[G0]

