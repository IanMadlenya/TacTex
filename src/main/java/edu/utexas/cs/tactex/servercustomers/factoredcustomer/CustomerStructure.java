/*
 * TacTex - a power trading agent that competed in the Power Trading Agent Competition (Power TAC) www.powertac.org
 * Copyright (c) 2013-2016 Daniel Urieli and Peter Stone {urieli,pstone}@cs.utexas.edu               
 *
 *
 * This file is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This file is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * This file incorporates work covered by the following copyright and  
 * permission notice:  
 *
 *     Copyright 2011 the original author or authors.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an
 *     "AS IS" BASIS,  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 *     either express or implied. See the License for the specific language
 *     governing permissions and limitations under the License.
 */

package edu.utexas.cs.tactex.servercustomers.factoredcustomer;

import org.w3c.dom.*;

/**
 * Data-holder class for parsed configuration elements of one customer.
 * All members are declared final in the package scope.
 * 
 * @author Prashant Reddy
 */
public final class CustomerStructure
{
    enum EntityType { RESIDENTIAL, COMMERCIAL, INDUSTRIAL };
    	
    private final Element configXml;
    
    private static long structureCounter = 0;
    
    final long structureId = ++structureCounter;
    final String name;
    final String creatorKey;
    final EntityType entityType;
    
    CustomerStructure(String nameWithCount, Element xml)
    {
        name = nameWithCount;
        configXml = xml;
        
        creatorKey = xml.getAttribute("creatorKey");
        
        entityType = Enum.valueOf(EntityType.class, xml.getAttribute("entityType"));
    }

    public Element getConfigXml()
    {
        return configXml;
    }
        
} // end class
