#deploy
mvn clean package deploy -Pdev
#post deploy lanciare sul server:
sudo systemctl restart riderpay
sudo systemctl status riderpay
#controllo log:
journalctl -u riderpay -n 50 --no-pager