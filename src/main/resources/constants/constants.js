exports.refData = {
    SC_Status: ['Draft Contract', 'Pending Agreement', 'Signed and Agreed'],
    LC_Status: [
        'Draft',
        'LCRequested',
        'Issuance-Pending Approval',
        'Issued-Confirmation Requested',
        'Confirmation-Pending Approval',
        'Confirmed',
        'LC Accepted'
    ],
    VN_Status:['Draft','Vessel Nominated'],
    User_Roles:['TRADER','OPERATOR','APPROVER']
}


exports.CERTIFICATES = {
    certificateOfOrigin: {
      title: 'Certificate of Origin',
      wireName:'certificateOfOrigin',
      linkedRole:'COOPROVIDER'
    },
    qualityCertificate: {
      title: 'Certificate of Quality',
      wireName:'qualityCertificate',
      linkedRole:'SUPERINTENDENT'
    },
    certificateOfChemicalResidues: {
      title: 'Certificate of Chemical Residues',
      wireName:'certificateOfChemicalResidues',
      linkedRole:'SUPERINTENDENT'
    },
    officialPhytosanitaryCertificate: {
      title: 'Phytosanitary Certificate',
      wireName:'officialPhytosanitaryCertificate',
      linkedRole:'PHYTOINSPECTOR'
    },
    phytosanitaryCertificate: {
      title: 'Phytosanitary Certificate',
      wireName:'phytosanitaryCertificate',
      linkedRole:'PHYTOINSPECTOR'
    },
    billOfLading: {
      title: 'Bill of Lading',
      wireName:'billOfLading',
      linkedRole:'SHIPPINGAGENT'
    },
    officialGrainWeightCertificate: {
      title: 'Grain Weight Certificate',
      wireName:'officialGrainWeightCertificate',
      linkedRole:'GRAININSPECTOR'
    },
    Default:{
      title:"Default",
      wireName:"Default",
      linkedRole:'OPERATOR'
    },
    mapWireNameToRole :(wireName)=>{
       return this.CERTIFICATES[wireName] ? this.CERTIFICATES[wireName].linkedRole :undefined;
    }
  };
  
exports.certificateToRole = {
    "Bill of Lading" : "SHIPPINGAGENT",
    "Certificate of Quality":"SUPERINTENDENT",
    "Certificate of Chemical Residues":"SUPERINTENDENT",
    "Certificate of Origin":"COOPROVIDER",
    "Phytosanitary Certificate":"PHYTOINSPECTOR",
    "Grain Weight Certificate":"GRAININSPECTOR",
    "Default" : "OPERATOR"//test
}


// NOTE: these displaynames are not consistant with the create SC form.

