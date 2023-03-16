# -*- mode: ruby -*-
# vi: set ft=ruby :

Vagrant.configure("2") do |config|

  microservices = [
    { :name => :nfs,       :ip => "10.0.0.10", :memory => 256, :cpu => 2 },
    { :name => :rabbitmq,  :ip => "10.0.0.11", :memory => 512, :cpu => 2 },
    { :name => :config,    :ip => "10.0.0.12", :memory => 512, :cpu => 2 },
    { :name => :discovery, :ip => "10.0.0.13", :memory => 512, :cpu => 2 },
    { :name => :gateway,   :ip => "10.0.0.14", :memory => 512, :cpu => 2 },
    { :name => :wfs,       :ip => "10.0.0.15", :memory => 4096, :cpu => 2 },
    { :name => :wms,       :ip => "10.0.0.16", :memory => 4096, :cpu => 2 },
    { :name => :wcs,       :ip => "10.0.0.17", :memory => 4096, :cpu => 2 },
    { :name => :rest,      :ip => "10.0.0.18", :memory => 4096, :cpu => 2 },
    { :name => :webui,     :ip => "10.0.0.19", :memory => 4096, :cpu => 2 },
    { :name => :gwc,       :ip => "10.0.0.20", :memory => 4096, :cpu => 2 },
  ]

  microservices.each do |val|
    config.vm.define val[:name].to_s do |subconfig|
       subconfig.vm.box      = "debian/bullseye64"
       subconfig.vm.hostname = val[:name].to_s
       subconfig.vm.network :private_network, ip: val[:ip]
       subconfig.vm.provider "virtualbox" do |vb|
         vb.memory = val[:memory]
         vb.cpus   = val[:cpu]
       end

      subconfig.vm.provision :ansible do |ansible|
      ansible.playbook           = "playbooks/universe.yaml"
      ansible.inventory_path     = "inventories/vagrant.yaml"
      ansible.compatibility_mode = "2.0"
      #ansible.verbose = "-vvv"
    end

    end
  end
end
