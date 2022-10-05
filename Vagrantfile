# -*- mode: ruby -*-
# vi: set ft=ruby :

Vagrant.configure("2") do |config|

  microservices = [
    { :name => :nfs,      :ip => "10.0.0.10" },
    { :name => :admin,    :ip => "10.0.0.11" },
    #{ :name => :gateway,  :ip => "10.0.0.12" },
    #{ :name => :wfs,      :ip => "10.0.0.13" },
    #{ :name => :wms,      :ip => "10.0.0.14" },
    #{ :name => :wcs,      :ip => "10.0.0.15" },
    #{ :name => :rest,     :ip => "10.0.0.16" },
    #{ :name => :webui,    :ip => "10.0.0.17" },
    #{ :name => :gwc,      :ip => "10.0.0.18" },
  ]

  microservices.each_with_index do |val, i|
    config.vm.define val[:name].to_s do |subconfig|
       subconfig.vm.box      = "debian/bullseye64"
       subconfig.vm.hostname = val[:name].to_s
       subconfig.vm.network :private_network, ip: val[:ip]
       subconfig.vm.provider "virtualbox" do |vb|
         vb.memory = 1024
         vb.cpus   = 2
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
