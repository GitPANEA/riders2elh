#deploy
mvn clean package deploy -Pdev
#post deploy lanciare sul server:
sudo systemctl restart riders2eLH
sudo systemctl status riders2eLH
#controllo log:
journalctl -u riders2eLH -n 50 --no-pager
